package dev.sympho.google_group_resolver;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.validation.constraints.NotNull;

import com.google.common.collect.Streams;

import org.checkerframework.checker.interning.qual.UnknownInterned;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Assorted helper utilities.
 */
public final class Utils {

    /** Do not instantiate. */
    private Utils() {}

    /**
     * Counts similar objects in the given stream.
     *
     * @param <T> The source object type.
     * @param <K> The classifier type.
     * @param values The values to count.
     * @param classifier A function that maps values into a category for matching.
     * @return The count of objects for each classifier seen.
     */
    public static <T, K> Map<K, Long> countBy( 
        final Stream<T> values, 
        final Function<T, K> classifier 
    ) {

        return values.collect( Collectors.groupingBy( 
            classifier, 
            Collectors.counting() 
        ) );

    }

    /**
     * Counts similar objects in the given iterable.
     *
     * @param <T> The source object type.
     * @param <K> The classifier type.
     * @param values The values to count.
     * @param classifier A function that maps values into a category for matching.
     * @return The count of objects for each classifier seen.
     */
    public static <T, K> Map<K, Long> countBy( 
        final Iterable<T> values, 
        final Function<T, K> classifier 
    ) {

        return countBy( Streams.stream( values ), classifier );

    }

    /**
     * Executes operations on the current observation, if one exists.
     *
     * @param registry The observation registry.
     * @param handler The handler to apply.
     */
    @SuppressWarnings( "tainting:argument" )
    public static void ifObservation( 
        final ObservationRegistry registry, 
        final Consumer<? super @NotNull @UnknownInterned Observation> handler 
    ) {

        final var observation = registry.getCurrentObservation();
        if ( observation != null ) {
            handler.accept( observation );
        }

    }
    
}
