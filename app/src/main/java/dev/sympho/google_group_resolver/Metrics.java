package dev.sympho.google_group_resolver;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;

import io.micrometer.observation.ObservationRegistry;

/**
 * Utilities for dealing with metrics.
 */
public final class Metrics {

    /** The base metric name for the application. */
    public static final MetricName APP_BASE = new MetricName( "groupresolver" );

    /** The base metric name for the directory service component. */
    public static final MetricName DIRECTORY_BASE = APP_BASE.extend( "google", "directory" );

    /** Do not instantiate. */
    private Metrics() {}

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
