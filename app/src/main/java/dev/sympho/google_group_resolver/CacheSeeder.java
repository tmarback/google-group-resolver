package dev.sympho.google_group_resolver;

import java.time.Duration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.sympho.google_group_resolver.CacheSettings.SeederSettings;
import dev.sympho.google_group_resolver.google.DirectoryGroup;
import dev.sympho.google_group_resolver.google.DirectoryService;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.Disposable;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetrySpec;

/**
 * Seeder that initializes the cache with data to reduce the penalty of the warmup period.
 */
public class CacheSeeder {

    /** Base metric name. */
    public static final Metrics.MetricName METRIC_BASE = Metrics.APP_BASE.extend( "seeder" );

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( CacheSeeder.class );

    /** The client to use. */
    private final DirectoryService directory;

    /** The cache to seed. */
    private final GroupCache cache;

    /** The re-seeding period. */
    private final Duration period;
    
    /** The retry policy on pipeline error. */
    private final Retry retry;

    /** The observation registry in use. */
    private final ObservationRegistry observations;

    /** The active runner. */
    private @Nullable Disposable runner;

    /**
     * Creates a new instance.
     *
     * @param directory The client to use.
     * @param cache The cache to seed.
     * @param settings The settings.
     * @param observations The observation registry to use.
     */
    public CacheSeeder( 
        final DirectoryService directory, 
        final GroupCache cache, 
        final SeederSettings settings,
        final ObservationRegistry observations 
    ) {

        this.directory = directory;
        this.cache = cache;
        this.period = settings.period();
        this.retry = RetrySpec.fixedDelay( Long.MAX_VALUE, settings.period() )
            .transientErrors( true );

        this.observations = observations;

    }

    /**
     * Seeds the cache with existing groups.
     *
     * @return A mono that completes once seeding is done.
     */
    private Mono<Void> seedCache() {

        return directory.getGroups()
            .map( DirectoryGroup::email )
            .flatMap( email -> cache.get( email ).latest() )
            .count()
            .doOnSubscribe( s -> LOG.debug( "Seeding cache" ) )
            .doOnSuccess( c -> LOG.info( "Seeded cache with {} entries", c ) )
            .doOnError( ex -> LOG.error( "Cache seeding encountered an error", ex ) )
            .onErrorComplete()
            .then()
            .name( METRIC_BASE.name() )
            .tap( Micrometer.observation( observations ) );

    }

    /**
     * Runs the seeder.
     */
    @PostConstruct
    public synchronized void start() {

        if ( period.isZero() ) {

            LOG.info( "Running one-off cache seeder" );

            seedCache().subscribe();

        } else if ( period.isNegative() ) {

            LOG.info( "Cache seeder is disabled" );

        } else if ( runner == null ) {

            LOG.info( "Starting cache seeder" );

            this.runner = Flux.interval( Duration.ZERO, period )
                .onBackpressureDrop( i -> LOG.warn( 
                    "Previous cache seeding is still running" 
                ) )
                .concatMap( i -> seedCache(), 0 )
                .retryWhen( retry )
                .subscribe();

        }

    }

    /**
     * Stops the seeder.
     */
    @PreDestroy
    public synchronized void stop() {

        if ( runner != null ) {

            LOG.info( "Stopping cache seeder" );

            this.runner.dispose();
            this.runner = null;

        }

    }
    
}
