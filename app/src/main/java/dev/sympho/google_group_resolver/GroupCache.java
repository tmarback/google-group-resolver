package dev.sympho.google_group_resolver;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import reactor.core.publisher.Mono;

/**
 * Cache of groups that entities belong to.
 */
public interface GroupCache {

    /**
     * Fetches the current entry for an entity.
     *
     * @param email The email of the entity to lookup.
     * @return The current entry for that entity. May be not currently valid.
     */
    Mono<Entry> get( String email );

    /**
     * Updates the entry for an entity.
     *
     * @param email The email of the entity to lookup.
     * @return The updated entry.
     */
    Mono<Entry> update( String email );

    /**
     * Retrieves the number of entries currently stored in this cache.
     *
     * @return The current number of entries.
     */
    @SideEffectFree
    long size();

    /**
     * Retrieves the capacity of the cache.
     *
     * @return The capacity.
     * @implSpec To allow for optimizing access performance, the capacity is <b>not</b> required 
     *           to be a strict limit on the {@link #size() number of entries} in the cache; 
     *           rather, it is only defined as a target that implementations should attempt to
     *           stay under <i>on average</i> through arbitrary methods. This implies that the
     *           number of entries <i>may</i> temporarily exceed the capacity.
     */
    @Pure
    long capacity();

    /**
     * An entry in the cache.
     */
    interface Entry {

        /**
         * Whether the entry is currently valid.
         *
         * @return {@code true} if the entry is valid,
         *         {@code false} otherwise.
         */
        @SideEffectFree
        boolean valid();

        /**
         * Retrieves the value cached in this entry.
         *
         * @return The cached value.
         */
        @SideEffectFree
        List<DirectoryGroup> value();

    }

    /**
     * A loaded entry.
     *
     * @param value The entry value.
     * @param staleOn When the entry will become stale.
     */
    record LoadedEntry(
        List<DirectoryGroup> value,
        Instant staleOn
    ) implements Entry {

        @Override
        public boolean valid() {

            return staleOn.isAfter( Instant.now() );

        }

        /**
         * Creates an instance.
         *
         * @param value The value of the entry.
         * @param ttlLive After how long the entry becomes stale.
         * @return The instance.
         */
        public static LoadedEntry of( final List<DirectoryGroup> value, final Duration ttlLive ) {

            return new LoadedEntry( value, Instant.now().plus( ttlLive ) );

        }

    }
    
}
