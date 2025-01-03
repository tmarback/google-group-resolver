package dev.sympho.google_group_resolver;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.checkerframework.dataflow.qual.Pure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Flux;

/**
 * Group resolver that includes indirect group memberships.
 */
public class RecursiveGroupResolver implements GroupResolver {

    /** Metric tag for the query value. */
    public static final String METRIC_TAG_QUERY_VALUE = "query.value";

    /** Metric tag for the query parent. */
    public static final String METRIC_TAG_QUERY_PARENT = "query.parent";

    /** Base metric name. */
    public static final Metrics.MetricName METRIC_BASE = Metrics.APP_BASE.extend( "resolver" );

    /** Metric for resolution entrypoint. */
    public static final Metrics.MetricName METRIC_MAIN = METRIC_BASE.extend( "main" );

    /** Metric for resolution recursion. */
    public static final Metrics.MetricName METRIC_RECURSION = METRIC_BASE.extend( "recurse" );

    /** Metric for resolution query. */
    public static final Metrics.MetricName METRIC_QUERY = METRIC_BASE.extend( "query" );

    /** Metric for prefetching. */
    public static final Metrics.MetricName METRIC_PREFETCH = METRIC_BASE.extend( "prefetch" );

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( RecursiveGroupResolver.class );

    /** The cache to fetch from. */
    private final GroupCache cache;

    /** Whether to prefetch using stale data. */
    private final boolean prefetch;

    /** The observation registry in use. */
    private final ObservationRegistry observations;

    /**
     * Creates a new instance.
     *
     * @param cache The cache to fetch from.
     * @param prefetch If {@code true}, prefetches nested groups using stale cache data.
     * @param observations The observation registry to use.
     */
    @Pure
    public RecursiveGroupResolver( 
        final GroupCache cache, 
        final boolean prefetch, 
        final ObservationRegistry observations 
    ) {

        this.cache = cache;
        this.prefetch = prefetch;
        this.observations = observations;

    }

    /**
     * Retrieves the indirect groups from a list of direct groups.
     *
     * @param email The email of the entity being resolved.
     * @param groups The direct groups.
     * @param seen The groups that were already visited and should be skipped if seen again.
     * @return The indirect groups.
     */
    private Flux<DirectoryGroup> getIndirectGroups( 
        final String email,
        final List<DirectoryGroup> groups, 
        final Set<String> seen 
    ) {

        final var updatedSeen = new HashSet<>( seen );
        final var newGroups = groups.stream()
            .map( DirectoryGroup::email )
            .filter( updatedSeen::add )
            .toList();

        final var seenArg = Collections.unmodifiableSet( updatedSeen );
        return Flux.fromIterable( newGroups ).flatMap( e -> getGroupsFor( e, seenArg )
            .checkpoint( "Resolution (recursion)" )
            .name( METRIC_RECURSION.name() )
            .doOnSubscribe( s -> Metrics.addHighCardinalityKeyValue( 
                observations, 
                METRIC_TAG_QUERY_VALUE, e 
            ) )
            .doOnSubscribe( s -> Metrics.addHighCardinalityKeyValue( 
                observations, 
                METRIC_TAG_QUERY_PARENT, email 
            ) )
            .tap( Micrometer.observation( observations ) )
        );

    }

    /**
     * Retrieves the groups (direct or indirect) for the entity identified by the given email.
     *
     * @param email The email of the entity to resolve.
     * @param seen The groups that were already visited and should be skipped if seen again.
     * @return The indirect groups.
     */
    private Flux<DirectoryGroup> getGroupsFor( final String email, final Set<String> seen ) {

        final var entry = cache.get( email );
        
        final Flux<DirectoryGroup> prefetcher;
        if ( prefetch && !entry.valid() ) {
            final var cached = entry.value();
            if ( cached != null ) { // Stale but non-expired cache value, use for prefetch
                // Don't need to wait for the prefetch to finish, just let it run in the background
                prefetcher = getIndirectGroups( email, cached, seen )
                    .checkpoint( "Resolution (prefetch)" )
                    .name( METRIC_PREFETCH.name() )
                    .doOnSubscribe( s -> Metrics.addHighCardinalityKeyValue( 
                        observations, 
                        METRIC_TAG_QUERY_VALUE, email 
                    ) )
                    .tap( Micrometer.observation( observations ) )
                    .cache(); // Don't cancel the prefetch
            } else {
                prefetcher = Flux.empty();
            }
        } else {
            prefetcher = Flux.empty();
        }

        return entry.latest()
            .filter( g -> !g.isEmpty() ) // Skip processing nested if empty
            .flatMapMany( groups -> Flux.fromIterable( groups )
                .mergeWith( getIndirectGroups( email, groups, seen ) )
            )
            .checkpoint( "Resolution (fetch)" )
            .name( METRIC_QUERY.name() )
            .doOnSubscribe( s -> Metrics.addHighCardinalityKeyValue( 
                observations, 
                METRIC_TAG_QUERY_VALUE, email 
            ) )
            .tap( Micrometer.observation( observations ) )
            // or() so that the prefetch keeps the right context 
            // (which doesn't happen with subscribe())
            // never() so the or() always selects the real values
            .or( prefetcher.thenMany( Flux.never() ) );

    }

    @Override
    public Flux<DirectoryGroup> getGroupsFor( final String email ) {

        return getGroupsFor( Objects.requireNonNull( email ), Set.of( email ) )
            .distinct( DirectoryGroup::email ) // Don't allow duplicate emails through
            .doOnSubscribe( s -> LOG.trace( "Resolving groups for {}", email ) )
            .name( METRIC_MAIN.name() )
            .doOnSubscribe( s -> Metrics.addHighCardinalityKeyValue( 
                observations, 
                METRIC_TAG_QUERY_VALUE, email 
            ) )
            .tap( Micrometer.observation( observations ) );

    }
    
}
