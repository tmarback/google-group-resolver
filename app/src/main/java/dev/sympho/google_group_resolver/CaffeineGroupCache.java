package dev.sympho.google_group_resolver;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.sympho.google_group_resolver.google.DirectoryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Group cache backed by a Caffeine cache.
 */
public class CaffeineGroupCache implements GroupCache {

    /** Base metric name. */
    public static final Metrics.MetricName METRIC_BASE = Metrics.APP_BASE.extend( "cache" );

    /** Metric for cache lookups. */
    public static final Metrics.MetricName METRIC_LOOKUPS = METRIC_BASE.extend( "lookup" );

    /** Metric for cache updates. */
    public static final Metrics.MetricName METRIC_UPDATE = METRIC_BASE.extend( "update" );

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( CaffeineGroupCache.class );

    /** The backing cache. */
    private final AsyncLoadingCache<String, GroupCache.Entry> cache;

    /** The target capacity of the cache. */
    private final long capacity;

    /** The observation registry in use. */
    private final ObservationRegistry observations;

    /**
     * Creates a new instance.
     *
     * @param directory Client to use to fetch new data.
     * @param ttlLive How long after being fetched that data should become stale.
     * @param ttlStale How long after becoming stale that data should become expired.
     * @param capacity The target capacity of the cache.
     * @param meters The meter registry to use.
     * @param observations The observation registry to use.
     */
    public CaffeineGroupCache(
        final DirectoryService directory, 
        final Duration ttlLive, 
        final Duration ttlStale,
        final long capacity,
        final MeterRegistry meters,
        final ObservationRegistry observations
    ) {

        this.capacity = capacity;
        this.observations = observations;

        this.cache = Caffeine.newBuilder()
            .scheduler( Scheduler.systemScheduler() )
            .recordStats()
            .maximumSize( capacity )
            // refresh() will be called anyway if stale, but might as well issue the refresh
            // while that happens since the refresh is deduplicated
            .refreshAfterWrite( ttlLive )
            .expireAfterWrite( ttlStale.plus( ttlLive ) )
            .buildAsync( ( email, executor ) -> directory.getGroupsFor( email )
                .collectList()
                .map( gs -> LoadedEntry.of( gs, ttlLive ) )
                .doOnError( ex -> LOG.debug( "Error during update for {}: {}", email, ex ) )
                .subscribeOn( Schedulers.fromExecutor( executor ) )
                .toFuture()
            );

        // Record metrics
        CaffeineCacheMetrics.monitor( meters, this.cache, "group-cache" );

    }

    @Override
    public Mono<Entry> get( final String email ) {

        return Mono.fromFuture( () -> cache.get( email ) )
            .publishOn( Schedulers.parallel() )
            .checkpoint( "Cache lookup" )
            .name( METRIC_LOOKUPS.name() )
            .tap( Micrometer.observation( observations ) );

    }

    @Override
    public Mono<Entry> update( final String email ) {

        return Mono.fromFuture( () -> cache.synchronous().refresh( email ) )
            .publishOn( Schedulers.parallel() )
            .checkpoint( "Cache update" )
            .name( METRIC_UPDATE.name() )
            .tap( Micrometer.observation( observations ) );

    }

    @Override
    public long size() {

        return cache.synchronous().estimatedSize();

    }

    @Override
    public long capacity() {

        return capacity;

    }

    /**
     * Triggers a clean-up of the backing cache.
     */
    void cleanUp() {

        cache.synchronous().cleanUp();

    }
    
}
