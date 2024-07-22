package dev.sympho.google_group_resolver;

import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;
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
     * @return The current entry for that entity.
     */
    Entry get( String email );

    /**
     * Retrieves the number of entries currently stored in this cache.
     *
     * @return The current number of entries.
     */
    @SideEffectFree
    int size();

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
    int capacity();

    /**
     * An entry in the cache.
     *
     * @apiNote While {@link #latest()} is defined to continue working indefinitely, keeping an
     *          entry stored long-term to retrieve the updated value with {@link #latest()} may
     *          cause worse performance and increased memory consumption; it is expected (and
     *          strongly recommended) that an entry is used promptly upon being acquired, and
     *          then discarded.
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
         * <p>The entry has a value if it is currently {@link #valid()}, or is stale but not 
         * expired. Otherwise, there is no cached value, and this method returns {@code null}.
         *
         * @return The cached value, if any.
         */
        @SideEffectFree
        @Nullable List<DirectoryGroup> value();
            
        /**
         * Retrieves the latest group set of the entity cached under this entry. If this instance
         * is currently valid, the {@link #value() cached value} is used, otherwise using the value
         * cached on the latest entry (if any) or fetching the current value from the backend, as
         * necessary.
         *
         * @return The latest group set.
         * @apiNote The value is resolved upon subscription to the returned Mono, <i>not</i> at the
         *          time of calling this method; this means the Mono will resolve to the latest
         *          value at the time of subscription and may issue different results at different
         *          points in time. However, this method may display degraded performance on an
         *          old entry instance that precedes many refreshes; it is strongly recommended
         *          to always fetch an entry and use it promptly rather than storing it.
         */
        Mono<List<DirectoryGroup>> latest();

    }
    
}
