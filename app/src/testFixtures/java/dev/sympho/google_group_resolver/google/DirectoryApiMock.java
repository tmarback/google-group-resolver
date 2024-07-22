package dev.sympho.google_group_resolver.google;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock implementation of the {@link DirectoryApi} interface.
 * 
 * <p>This is used in lieu of a Mockito mock as pagination and batches are non-trivial
 * to set up so a direct implemetation is less burdensome to maintain.
 */
public class DirectoryApiMock implements DirectoryApi {

    /** Amount of groups in a page. */
    public static final int PAGE_SIZE = 3;

    /** The error message used when {@link #setThrowError(boolean) error mode} is enabled. */
    public static final String ERROR_MESSAGE_GLOBAL = "Error mode";

    /** The error message used when {@link #setHang(boolean) hang mode} is enabled. */
    public static final String ERROR_MESSAGE_QUERY = "Error query";    

    /** The logger. */
    private static final Logger LOG = LoggerFactory.getLogger( DirectoryApiMock.class );

    /** The group mapping to use. */
    private final SequencedMap<String, List<DirectoryGroup>> groupMap;

    /** Groups that some entity maps to but don't have their own mappings. */
    private final Set<String> extraGroups;

    /** Queries to throw errors for. */
    private final List<String> errorQueries;

    /** Whether to throw errors on calls. */
    @SuppressWarnings( "ExplicitInitialization" )
    private boolean throwError = false;

    /** Counts number of single requests. */
    private AtomicInteger counterSingle = new AtomicInteger();

    /** Counts number of batch requests. */
    private AtomicInteger counterBatch = new AtomicInteger();

    /**
     * Creates a new instance.
     *
     * @param groups The group mapping to use.
     * @param errorQueries Queries to throw errors for.
     * @param hangQueries Queries to hang for.
     */
    public DirectoryApiMock( 
            final Map<String, List<DirectoryGroup>> groups,
            final List<String> errorQueries
    ) {

        this.groupMap = Collections.unmodifiableSequencedMap( new LinkedHashMap<>( groups ) );
        this.errorQueries = List.copyOf( errorQueries );

        // Compute groups that exist but don't have their own mappings
        this.extraGroups = this.groupMap.values().stream()
                .flatMap( gs -> gs.stream() )
                .map( DirectoryGroup::email )
                .filter( email -> !this.groupMap.containsKey( email ) )
                .collect( Collectors.toSet() );

    }

    /**
     * Sets whether method calls should result in errors.
     *
     * @param throwError If {@code true}, operations will always result in errors.
     */
    public void setThrowError( final boolean throwError ) {

        this.throwError = throwError;

    }

    /**
     * Resolves a group query.
     *
     * @param email The email to query.
     * @param pageToken The page token.
     * @return The query result.
     * @throws DirectoryApi.RequestFailedException if the query failed.
     */
    private DirectoryApi.Result query( final String email, final @Nullable String pageToken )
            throws DirectoryApi.RequestFailedException {

        LOG.trace( "Mock querying {}:{}", email, pageToken );

        if ( errorQueries.contains( email ) ) {
            LOG.trace( "Query error triggered" );
            throw new IllegalArgumentException( ERROR_MESSAGE_QUERY );
        }

        final var groups = groupMap.get( email );
        if ( groups == null ) {
            if ( extraGroups.contains( email ) ) {
                return new DirectoryApi.Result( Stream.empty(), null );
            } else {
                throw new DirectoryApi.RequestFailedException( 404, "Unknown email" );
            }
        }

        // Calculate page bounds
        final var page = pageToken == null ? 0 : Integer.parseInt( pageToken );
        final var startIndex = page * PAGE_SIZE;
        final var endIndex = Math.min( ( page + 1 ) * PAGE_SIZE, groups.size() );

        // Get page content
        final var result = groups.subList( startIndex, endIndex );
        final var next = endIndex == groups.size() ? null : String.valueOf( page + 1 );

        return new DirectoryApi.Result( result.stream(), next );

    }

    /**
     * Checks if any global conditions need to be applied.
     */
    private void checkGlobalConditions() {

        if ( throwError ) {
            LOG.trace( "Global error triggered" );
            throw new IllegalArgumentException( ERROR_MESSAGE_GLOBAL );
        }

    }

    @Override
    public Result getGroups( final String email, final @Nullable String nextPageToken )
            throws IOException, RequestFailedException {

        LOG.trace( "Mocking single {}:{}", email, nextPageToken );
        counterSingle.incrementAndGet();

        checkGlobalConditions();

        return query( email, nextPageToken );

    }

    @Override
    @SuppressWarnings( "IllegalCatch" )
    public void getGroupsBatch( final Collection<BatchRequest> requests )
            throws IllegalArgumentException {

        LOG.trace( "Mocking batch with {} elements", requests.size() );
        counterBatch.incrementAndGet();

        checkGlobalConditions();

        for ( final var request : requests ) {

            LOG.trace( "Batch member {}", request );
            
            try {
                request.callback().onSuccess( query( request.email(), request.nextPageToken() ) );
            } catch ( final DirectoryApi.RequestFailedException ex ) {
                request.callback().onFailure( ex );
            } catch ( final Exception ex ) {
                request.callback().onError( ex );
            }

        }

    }

    /**
     * Retrieves the number of single requests made.
     *
     * @return The number of requests.
     */
    public int singleCount() {
        return counterSingle.get();
    }

    /**
     * Retrieves the number of batch requests made.
     *
     * @return The number of requests.
     */
    public int batchCount() {
        return counterBatch.get();
    }
    
}
