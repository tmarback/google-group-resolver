package dev.sympho.google_group_resolver;

import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Utilities for dealing with metrics.
 */
public final class Metrics {

    /** The base metric name for the application. */
    public static final MetricName APP_BASE = new MetricName( "groupresolver" );

    /** The base metric name for the directory service component. */
    public static final MetricName DIRECTORY_BASE = APP_BASE.extend( "google", "directory" );

    /** Registry used to {@link #instrumentSchedulers() instrument reactor schedulers}. */
    public static final CompositeMeterRegistry SCHEDULER_REGISTRY = new CompositeMeterRegistry();

    /** Do not instantiate. */
    private Metrics() {}

    /**
     * Attempts to determine the name of a scheduler from the thread factory.
     *
     * @param threadFactory The thread factory for the scheduler.
     * @param defaultName The default name to use if none is found.
     * @return The inferred scheduler name.
     */
    // https://github.com/reactor/reactor-core/issues/3285
    private static String inferSimpleSchedulerName( 
            final ThreadFactory threadFactory, final String defaultName ) {

        if ( !( threadFactory instanceof Supplier ) ) {
            return defaultName;
        }
        final Object supplied = ( ( Supplier<?> ) threadFactory ).get();
        if ( !( supplied instanceof String ) ) {
            return defaultName;
        }
        return ( String ) supplied;

    }

    /**
     * Instruments Reactor's scheduler factory using {@link #SCHEDULER_REGISTRY}.
     */
    public static void instrumentSchedulers() {

        Schedulers.setFactory( new Schedulers.Factory() {

            private Scheduler newScheduler(
                final Scheduler backing,
                final ThreadFactory threadFactory,
                final String type
            ) {

                return Micrometer.timedScheduler(
                    backing, 
                    SCHEDULER_REGISTRY, 
                    "reactor",
                    Tags.of( 
                        Tag.of( "scheduler.type", type ),
                        Tag.of( 
                            "scheduler.name", 
                            inferSimpleSchedulerName( threadFactory, type + "???" ) 
                        )
                    )
                );

            }

            @Override
            public Scheduler newBoundedElastic( 
                final int threadCap, final int queuedTaskCap, 
                final ThreadFactory threadFactory, final int ttlSeconds 
            ) {

                return newScheduler( 
                    Schedulers.Factory.super.newBoundedElastic( 
                        threadCap, queuedTaskCap, 
                        threadFactory, ttlSeconds 
                    ), 
                    threadFactory, 
                    "boundedElastic" 
                );

            }

            @Override
            public Scheduler newParallel( 
                final int parallelism, final ThreadFactory threadFactory 
            ) {

                return newScheduler( 
                    Schedulers.Factory.super.newParallel( parallelism, threadFactory ), 
                    threadFactory, 
                    "parallel" 
                );

            }

            @Override
            public Scheduler newSingle( final ThreadFactory threadFactory ) {

                return newScheduler( 
                    Schedulers.Factory.super.newSingle( threadFactory ), 
                    threadFactory, 
                    "single" 
                );

            }
            
        } );

    }

    /**
     * Adds a low cardinality key value to the current observation, if one exists.
     *
     * @param registry The observation registry in use.
     * @param tagKey The key.
     * @param tagValue The value.
     */
    public static void addLowCardinalityKeyValue( 
        final ObservationRegistry registry,
        final String tagKey, 
        final String tagValue
    ) {

        Utils.ifObservation( 
            registry, 
            observation -> observation.lowCardinalityKeyValue( tagKey, tagValue ) 
        );

    }

    /**
     * Adds a high cardinality key value to the current observation, if one exists.
     *
     * @param registry The observation registry in use.
     * @param tagKey The key.
     * @param tagValue The value.
     */
    public static void addHighCardinalityKeyValue( 
        final ObservationRegistry registry,
        final String tagKey, 
        final String tagValue
    ) {

        Utils.ifObservation( 
            registry, 
            observation -> observation.highCardinalityKeyValue( tagKey, tagValue ) 
        );

    }

    /**
     * Extends a metric name.
     *
     * @param base The base name.
     * @param names The additional name components to append.
     * @return The composed name.
     */
    @Pure
    // @StaticallyExecutable // Doesn't work with varargs currently
    public static String extendName( final String base, final String... names ) {

        return Stream.concat( 
            Stream.of( base ), 
            Stream.of( names ) 
        ).collect( Collectors.joining( "." ) ).intern();

    }

    /**
     * The name of a metric.
     *
     * @param name The name.
     */
    public record MetricName(
        String name
    ) {

        /**
         * Extends this name.
         *
         * @param names The additional name components to append.
         * @return The composed name.
         */
        @SideEffectFree
        public MetricName extend( final String... names ) {

            return new MetricName( extendName( name, names ) );

        }

    }
    
}
