package dev.sympho.google_group_resolver.google;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Stream;

import com.google.api.client.googleapis.batch.BatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonErrorContainer;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.Groups;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Client that access the Workspaces Directory API.
 */
@SuppressWarnings( "IllegalCatch" ) // Need to catch Exception to prevent task leaks
public class DirectoryApiClient implements DirectoryApi {

    /** The maximum amount of requests in a batch. */
    public static final int MAX_BATCH_SIZE = 1000;

    /** The API client. */
    private final Directory client;

    /**
     * Creates a new instance.
     *
     * @param client The API client to use.
     */
    public DirectoryApiClient( final Directory client ) {

        this.client = client;

    }

    /**
     * Prepares a group list request.
     *
     * @param email The user/group email.
     * @param nextPageToken The token for fetching the next page, if any.
     * @return The prepared request.
     * @throws IOException if an error occurred.
     */
    private Directory.Groups.List makeRequest( 
            final String email, 
            final @Nullable String nextPageToken 
    ) throws IOException {

        var request = client.groups().list()
                .setUserKey( email );
        
        if ( nextPageToken != null ) {
            request = request.setPageToken( nextPageToken );
        }

        return request;

    }

    /**
     * Prepares a result from the API response.
     *
     * @param result The API response.
     * @return The prepared result.
     */
    private static Result makeResult( final Groups result ) {

        final var groups = result.getGroups() == null
                ? Stream.<Group>empty()
                : result.getGroups().stream()
                        .map( group -> new Group( group.getName(), group.getEmail() ) );

        final var nextToken = result.getNextPageToken();
        
        return new DirectoryApi.Result( groups, nextToken );

    }

    @Override
    public Result getGroups( 
            final String email, 
            final @Nullable String nextPageToken 
    ) throws IOException, RequestFailedException {

        final var request = makeRequest( email, nextPageToken );
        try {
            final var result = request.execute();
            return makeResult( result );
        } catch ( final HttpResponseException ex ) {
            throw new RequestFailedException( ex.getStatusCode(), ex.getMessage() );
        }
        
    }

    @Override
    public void getGroupsBatch( 
            final Collection<BatchRequest> requests 
    ) throws IllegalArgumentException {

        if ( requests.isEmpty() ) {
            throw new IllegalArgumentException( "No requests in batch" );
        }

        if ( requests.size() > MAX_BATCH_SIZE ) {
            throw new IllegalArgumentException( 
                    "Too many requests for batch: " + requests.size() 
            );
        }

        final var batch = client.batch();

        final var queued = requests.stream().filter( request -> {
            try {
                batch.queue( 
                        makeRequest( 
                            request.email(), 
                            request.nextPageToken() 
                        ).buildHttpRequest(),
                        Groups.class, 
                        GoogleJsonErrorContainer.class, 
                        new RequestCallback( request.callback() ) 
                );
                return true;
            } catch ( final Exception ex ) {
                request.callback().onError( ex );
                return false;
            } 
        } ).toList();

        try {
            batch.execute();
        } catch ( final Exception ex ) {
            queued.forEach( request -> request.callback().onError( ex ) );
        }

    }

    /**
     * Callback for the batch request.
     *
     * @param callback The callback to foward results to.
     */
    private record RequestCallback(
            Callback callback
    ) implements BatchCallback<Groups, GoogleJsonErrorContainer> {

        @Override
        public void onSuccess( 
                final Groups result, 
                final HttpHeaders responseHeaders 
        ) throws IOException {

            callback.onSuccess( makeResult( result ) );
            
        }

        @Override
        public void onFailure( 
                final GoogleJsonErrorContainer error, 
                final HttpHeaders responseHeaders 
        ) throws IOException {

            final var code = error.getError().getCode();
            final var message = error.getError().getMessage();

            callback.onFailure( new RequestFailedException( code, message ) );

        }

    }
    
}
