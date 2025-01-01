package dev.sympho.google_group_resolver.google;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Stream;

import com.google.api.client.googleapis.batch.BatchCallback;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.json.GoogleJsonErrorContainer;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.Directory.Groups.List;
import com.google.api.services.directory.DirectoryRequest;
import com.google.api.services.directory.model.Groups;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.springframework.util.function.ThrowingSupplier;

import dev.sympho.google_group_resolver.Metrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Client that access the Workspaces Directory API.
 */
@SuppressWarnings( "IllegalCatch" ) // Need to catch Exception to prevent task leaks
public class DirectoryApiClient implements DirectoryApi {

    /** The maximum amount of requests in a batch. */
    public static final int MAX_BATCH_SIZE = 1000;

    /** Metric tag for the request type. */
    public static final String METRIC_TAG_REQUEST_TYPE = "request.type";

    /** Metric tag for the request count in a batch. */
    public static final String METRIC_TAG_REQUEST_COUNT = "request.count";

    /** Metric tag value for a single request. */
    public static final String METRIC_TAG_VALUE_SINGLE = "single";

    /** Metric tag value for a batch request. */
    public static final String METRIC_TAG_VALUE_BATCH = "batch";

    /** Base metric name. */
    public static final Metrics.MetricName METRIC_BASE = Metrics.DIRECTORY_BASE.extend( "api" );

    /** Metric name for API requests. */
    public static final Metrics.MetricName METRIC_REQUEST = METRIC_BASE.extend( "request" );

    /** Metric name for request execution. */
    public static final Metrics.MetricName METRIC_EXECUTE = METRIC_REQUEST.extend( "execute" );

    /** The API client. */
    private final Directory client;

    /** The observation registry in use. */
    private final ObservationRegistry observations;

    /**
     * Creates a new instance.
     *
     * @param client The API client to use.
     * @param observations The observation registry to use.
     */
    public DirectoryApiClient( final Directory client, final ObservationRegistry observations ) {

        this.client = client;
        this.observations = observations;

    }

    /**
     * Converts the given high-level API request into a handler request.
     *
     * @param source The source request.
     * @return The converted request.
     */
    private ApiRequest<?, ?, ?> convertRequest( final Request<?> source ) {

        return switch ( source ) {

            case GroupMembershipRequest r -> new GroupMembershipApiRequest( r );
            case GroupListRequest r -> new GroupListApiRequest( r );

        };

    }

    /**
     * Executes a request.
     *
     * @param <R> The interface result type.
     * @param <G> The API result type.
     * @param request The request to execute.
     * @throws IOException if an error occurred.
     */
    private <R extends @NonNull Result, G extends @NonNull Object> void executeRequest( 
            final ApiRequest<R, G, ?> request 
    ) throws IOException {

        // var here makes Checker crash
        final DirectoryRequest<G> rawRequest = request.createRequest( client );
        final var result = Observation.createNotStarted( METRIC_EXECUTE.name(), observations )
                .lowCardinalityKeyValue( METRIC_TAG_REQUEST_TYPE, METRIC_TAG_VALUE_SINGLE )
                .observe( ThrowingSupplier.of( () -> rawRequest.execute() ) );
        request.issueResult( result );

    }

    /**
     * Executes a request.
     *
     * @param request The request to execute.
     */
    public void doMakeRequest( final Request<?> request ) {

        try {
            executeRequest( convertRequest( request ) );
        } catch ( final HttpResponseException ex ) {
            request.callback().onFailure( 
                    new RequestFailedException( ex.getStatusCode(), ex.getMessage() ) 
            );
        } catch ( final Exception ex ) {
            request.callback().onError( ex );
        }

    }

    @Override
    public void makeRequest( final Request<?> request ) {

        Observation.createNotStarted( METRIC_REQUEST.name(), observations )
                .lowCardinalityKeyValue( METRIC_TAG_REQUEST_TYPE, METRIC_TAG_VALUE_SINGLE )
                .observe( () -> doMakeRequest( request ) );

    }

    /**
     * Enqueues a request into a batch.
     *
     * @param <R> The interface result type.
     * @param <G> The API result type.
     * @param batch The batch to queue into.
     * @param request The request to execute.
     * @throws IOException if an error occurred.
     */
    private <R extends Result, G extends @NonNull Object> void enqueueRequest( 
            final BatchRequest batch, 
            final ApiRequest<R, G, ?> request 
    ) throws IOException {

        batch.queue( 
                request.createRequest( client ).buildHttpRequest(),
                request.dataClass(), 
                GoogleJsonErrorContainer.class, 
                request
        );

    }

    /**
     * Executes a request batch.
     *
     * @param requests The requests to execute.
     * @throws IllegalArgumentException if the batch is empty.
     */
    public void doMakeRequestBatch( final Collection<? extends Request<?>> requests ) {

        if ( requests.isEmpty() ) {
            throw new IllegalArgumentException( "No requests in batch" );
        }

        if ( requests.size() > MAX_BATCH_SIZE ) {
            final var ex = new IllegalArgumentException( 
                    "Too many requests for batch: " + requests.size() 
            );
            requests.forEach( request -> request.callback().onError( ex ) );
            throw ex;
        }

        final var batch = client.batch();

        final var queued = requests.stream().filter( request -> {
            try {
                enqueueRequest( batch, convertRequest( request ) );
                return true;
            } catch ( final Exception ex ) {
                request.callback().onError( ex );
                return false;
            } 
        } ).toList();

        try {
            Observation.createNotStarted( METRIC_EXECUTE.name(), observations )
                    .lowCardinalityKeyValue( METRIC_TAG_REQUEST_TYPE, METRIC_TAG_VALUE_BATCH )
                    .highCardinalityKeyValue( 
                            METRIC_TAG_REQUEST_COUNT, 
                            String.valueOf( queued.size() ) 
                    )
                    .observe( () -> {
                        try {
                            batch.execute();
                        } catch ( final IOException ex ) {
                            throw new RuntimeException( ex );
                        }
                    } );
        } catch ( final Exception ex ) {
            queued.forEach( request -> request.callback().onError( ex ) );
        }

    }

    @Override
    public void makeRequestBatch( final Collection<? extends Request<?>> requests ) {

        Observation.createNotStarted( METRIC_REQUEST.name(), observations )
                .lowCardinalityKeyValue( METRIC_TAG_REQUEST_TYPE, METRIC_TAG_VALUE_BATCH )
                .highCardinalityKeyValue( 
                        METRIC_TAG_REQUEST_COUNT, 
                        String.valueOf( requests.size() ) 
                )
                .observe( () -> doMakeRequestBatch( requests ) );

    }

    /**
     * A request to make to the API.
     *
     * @param <R> The interface result type.
     * @param <G> The API result type.
     * @param <D> The API request type.
     */
    private interface ApiRequest<
                    R extends @NonNull Result, 
                    G extends @NonNull Object, 
                    D extends @NonNull DirectoryRequest<G>
            > extends BatchCallback<G, GoogleJsonErrorContainer> {

        /**
         * The underlying interface request.
         *
         * @return The request.
         */
        @Pure
        Request<R> sourceRequest();

        /**
         * The API data class.
         *
         * @return The data class.
         */
        @Pure
        Class<G> dataClass();

        /**
         * Creates the request object.
         *
         * @param directory The API client to use.
         * @return The request.
         * @throws IOException if an error occurred.
         */
        @SideEffectFree
        D createRequest( Directory directory ) throws IOException;

        /**
         * Parses the API result.
         *
         * @param raw The API result.
         * @return The interface result.
         */
        @SideEffectFree
        R parseResult( G raw );

        /**
         * Issues a result to the callback.
         *
         * @param result The API result.
         */
        default void issueResult( final G result ) {

            sourceRequest().callback().onSuccess( parseResult( result ) );

        }

        @Override
        default void onSuccess( 
                final G result, 
                final HttpHeaders responseHeaders 
        ) throws IOException {

            issueResult( result );
            
        }

        @Override
        default void onFailure( 
                final GoogleJsonErrorContainer error, 
                final HttpHeaders responseHeaders 
        ) throws IOException {

            final var code = error.getError().getCode();
            final var message = error.getError().getMessage();

            sourceRequest().callback().onFailure( new RequestFailedException( code, message ) );

        }

    }

    /**
     * API request for {@link GroupMembershipRequest}.
     *
     * @param sourceRequest The underlying interface request.
     */
    private record GroupMembershipApiRequest(
            GroupMembershipRequest sourceRequest
    ) implements ApiRequest<ListResult<DirectoryGroup>, Groups, Directory.Groups.List> {

        @Override
        public Class<Groups> dataClass() {
            return Groups.class;
        }

        @Override
        public List createRequest( final Directory directory ) throws IOException {

            var request = directory.groups().list()
                    .setUserKey( sourceRequest.email() );
            
            final var token = sourceRequest.nextPageToken();
            if ( token != null ) {
                request = request.setPageToken( token );
            }

            return request;

        }

        @Override
        public ListResult<DirectoryGroup> parseResult( final Groups raw ) {

            final var groups = raw.getGroups() == null
                    ? Stream.<DirectoryGroup>empty()
                    : raw.getGroups().stream()
                            .map( group -> new DirectoryGroup( 
                                    group.getName(), 
                                    group.getEmail() 
                            ) );

            final var nextToken = raw.getNextPageToken();
            
            return new ListResult<>( groups.toList(), nextToken );

        }

    }

    /**
     * API request for {@link GroupListRequest}.
     *
     * @param sourceRequest The underlying interface request.
     */
    private record GroupListApiRequest(
            GroupListRequest sourceRequest
    ) implements ApiRequest<ListResult<DirectoryGroup>, Groups, Directory.Groups.List> {

        @Override
        public Class<Groups> dataClass() {
            return Groups.class;
        }

        @Override
        public List createRequest( final Directory directory ) throws IOException {

            var request = directory.groups().list()
                    .setCustomer( "my_customer" );
            
            final var token = sourceRequest.nextPageToken();
            if ( token != null ) {
                request = request.setPageToken( token );
            }

            return request;

        }

        @Override
        public ListResult<DirectoryGroup> parseResult( final Groups raw ) {

            final var groups = raw.getGroups() == null
                    ? Stream.<DirectoryGroup>empty()
                    : raw.getGroups().stream()
                            .map( group -> new DirectoryGroup( 
                                    group.getName(), 
                                    group.getEmail() 
                            ) );

            final var nextToken = raw.getNextPageToken();
            
            return new ListResult<>( groups.toList(), nextToken );

        }

    }
    
}
