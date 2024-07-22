package dev.sympho.google_group_resolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.checkerframework.checker.interning.qual.UsesObjectEquals;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.util.NullnessUtil;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import dev.sympho.google_group_resolver.google.DirectoryService;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Implementation of the group cache using an LRU eviction strategy for trimming excess entries.
 * 
 * <p>Cache size is maintained by a background job that runs regularly, and is allowed
 * to spike above the capacity. Note that, with a sufficiently large working set size and
 * sufficiently fast access pattern, the cleanup job may not be capable of keeping the size near
 * the configured capacity; in that case, increase the capacity (uses more memory) or increase the
 * frequency of the cleanup task (uses more CPU time). It is generally recommended to use a capacity
 * large enough to fit the entire working set for best performance, especially if the access pattern
 * is evenly distributed over the working set (in which case running the cleanup task more 
 * frequently is likely to be ineffective).
 */
public class LRUGroupCache implements GroupCache {

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( LRUGroupCache.class );

    /** The backing entry map. */
    private final ConcurrentMap<String, EntryImpl> cache = new ConcurrentHashMap<>();

    /** RW lock to allow cleanup tasks to be isolated. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Client to use to fetch new data. */
    private final DirectoryService directory;

    /** How long after being fetched that data becomes stale. */
    private final Duration ttlLive;

    /** How long after becoming stale that data becomes expired. */
    private final Duration ttlStale;

    /** Period between runs of the cleaner. */
    private final Duration cleanerPeriod;

    /** The target capacity of the cache. */
    private final int capacity;

    /** The clock to use. */
    private final Clock clock;

    /** Active cleaner process. */
    private @Nullable Disposable cleaner;

    /**
     * Creates a new instance.
     *
     * @param directory Client to use to fetch new data.
     * @param ttlLive How long after being fetched that data should become stale.
     * @param ttlStale How long after becoming stale that data should become expired.
     * @param cleanerPeriod Period between runs of the cleaner.
     * @param capacity The target capacity of the cache.
     * @param clock The clock to use.
     */
    @Pure
    public LRUGroupCache( 
            final DirectoryService directory, 
            final Duration ttlLive, 
            final Duration ttlStale,
            final Duration cleanerPeriod,
            final int capacity,
            final Clock clock 
    ) {

        this.directory = Objects.requireNonNull( directory );
        this.ttlLive = Objects.requireNonNull( ttlLive );
        this.ttlStale = Objects.requireNonNull( ttlStale );
        this.cleanerPeriod = Objects.requireNonNull( cleanerPeriod );
        this.capacity = capacity;
        this.clock = Objects.requireNonNull( clock );

    }

    /**
     * Creates a new instance using the {@link Clock#systemUTC() default UTC system clock}.
     *
     * @param directory Client to use to fetch new data.
     * @param ttlLive How long after being fetched that data should become stale.
     * @param ttlStale How long after becoming stale that data should become expired.
     * @param cleanerPeriod Period between runs of the cleaner.
     * @param capacity The target capacity of the cache.
     */
    @Pure
    public LRUGroupCache( 
            final DirectoryService directory, 
            final Duration ttlLive,
            final Duration ttlStale,
            final Duration cleanerPeriod,
            final int capacity
    ) {

        this( directory, ttlLive, ttlStale, cleanerPeriod, capacity, Clock.systemUTC() );

    }

    @Override
    public int size() {

        return cache.size();

    }

    @Override
    public int capacity() {

        return capacity;

    }

    @Override
    public Entry get( final String email ) {

        final var l = lock.readLock();
        try {
            l.lock();
            return cache.computeIfAbsent( email, e -> new EntryImpl( email, null ) );
        } finally {
            l.unlock();
        }

    }

    /**
     * Trims expired and excess entries.
     */
    private void doClean() {

        LOG.debug( "Starting cleanup" );
        final var start = clock.instant();

        final List<Map.Entry<String, EntryImpl>> entries;
        final var l = lock.writeLock();
        try {
            l.lock(); // Can't allow modifications while iterating
            entries = List.copyOf( cache.entrySet() );
        } finally {
            l.unlock();
        }

        // Only get current entries while under lock so it can be released ASAP
        // This may result in some elements to be refreshed before deletion and thus not deleted,
        // which may cause size trimming to not reduce the size as much as expected; however, given
        // that deleting them under lock would only result in the elements being re-inserted again
        // once the lock is released, it does not actually make a difference. Thus, it's not worth
        // holding the lock for longer.

        // Remove entires that are too old
        final var valid = entries.stream()
                .filter( e -> {

                    final var entry = e.getValue();
                    if ( entry.expired() ) {
                        return true;
                    }

                    cache.remove( e.getKey(), e.getValue() );
                    return false;

                } )
                .toList();
        LOG.debug( "Removed {} expired entries", entries.size() - valid.size() );

        // If number of entries exceeds size, remove enough entries to match size
        final var excess = valid.size() - capacity;
        if ( excess > 0 ) {
            LOG.debug( "Cache size exceeded, removing {} entries", excess );
            if ( excess >= capacity ) {
                LOG.warn( 
                        "Cache size ({}) greatly exceeds the target ({})", 
                        valid.size(), 
                        capacity 
                );
            }

            // Sort from least recently accessed to most recently
            final var sorted = new ArrayList<>( valid );
            sorted.sort( ( e1, e2 ) -> e1.getValue().compareTo( e2.getValue() ) );

            // Remove entries with the oldest last-access timestamp first
            final var oldest = sorted.subList( 0, excess );
            oldest.forEach( e -> {
                if ( cache.remove( e.getKey(), e.getValue() ) ) {
                    LOG.trace( "Removed entry {}", e.getKey() );
                } else {
                    LOG.trace( "Entry {} was overwritten before deletion", e.getKey() );
                }
            } );
        }

        final var end = clock.instant();
        final var time = Duration.between( start, end );
        if ( time.compareTo( cleanerPeriod ) > 0 ) {
            LOG.warn( 
                    "Cleanup run took {}, which is longer than the configured period of {}",
                    time,
                    cleanerPeriod
            );
        } else {
            LOG.debug( "Cleanup run done ({})", time );
        }

    }

    /**
     * Starts the cleaner worker.
     */
    @PostConstruct
    public synchronized void startCleaner() {

        if ( cleaner == null ) {

            LOG.info( "Starting cache cleaner" );
            LOG.debug( "Cleaner period {}", cleanerPeriod );
            cleaner = Flux.interval( cleanerPeriod )
                    // Task may block waiting for the lock so change schedulers
                    .publishOn( Schedulers.boundedElastic() )
                    .onBackpressureDrop( c -> LOG.warn( "Cache cleaner can't keep up!" ) )
                    .concatMap( c -> Mono.fromRunnable( this::doClean ), 0 )
                    .repeat()
                    .retry()
                    .subscribe();

        }

    }

    /**
     * Stops the cleaner worker.
     */
    @PreDestroy
    public synchronized void stopCleaner() {

        if ( cleaner != null ) {

            LOG.info( "Stopping cache cleaner" );
            cleaner.dispose();
            cleaner = null;

        }

    }
    
    /**
     * An entry in the cache.
     */
    @UsesObjectEquals
    private final class EntryImpl implements Entry, Comparable<EntryImpl> {

        /** When the entry becomes stale. */
        private final Instant staleOn;
        /** When the entry becomes stale. */
        private final Instant expiredOn;

        /** The timestamp at which the cached value was last accessed. */
        private final AtomicReference<Instant> lastAccessed;

        /** The cached data, or {@code null} if this is a bootstrap entry. */
        private final @Nullable List<DirectoryGroup> cached;

        /**
         * When subscribed to, updates the cache entry in the backing map, 
         * and issues the new entry.
         */
        private final Mono<EntryImpl> next;

        /**
         * Creates a new entry.
         *
         * @param email The email of the entity that this entry represents.
         * @param cached The value to cache, if any.
         */
        private EntryImpl(
                final String email,
                final @Nullable List<DirectoryGroup> cached
        ) {

            this.cached = cached;

            final var now = clock.instant();
            this.staleOn = now.plus( ttlLive );
            this.expiredOn = staleOn.plus( ttlStale );

            this.lastAccessed = new AtomicReference<Instant>( now );

            // Prepare mono that updates the entry
            this.next = Flux.defer( () -> directory.getGroups( email ) )
                    .collectList()
                    .map( groups -> new EntryImpl( email, groups ) )
                    // Not having the explicit type makes Checker make some weird assumptions
                    .<EntryImpl>map( entry -> {
                        
                        // It is technically possible, if an entry is kept in memory (which is not
                        // recommended), for the current entry to expire and get removed and later
                        // be replaced by a new update chain.
                        // In that case, the most recent chain should always have priority even if 
                        // the current entry is older, to prevent bouncing between parallel chains.
                        // Thus, only replace the entry with the generated one if the cache slot is
                        // empty or still occupied by this entry, otherwise consider the latest in
                        // the new chain as the followup entry.
                        // (Note that a descendant of this entry cannot be in the cache yet as it 
                        // can only be generated by this mono, which is cached)
                        // This may trigger another fetch (discarding the fetch that was just
                        // completed) if the current latest entry is already no longer valid, but 
                        // is necessary for long-term safety. Either way this would only happen 
                        // with discouraged usage anyway (keeping an entry long-term).
                        final var l = lock.readLock();
                        final EntryImpl updated;
                        try {
                            l.lock();
                            updated = cache.compute( email, 
                                    ( e, current ) -> current == null || current == this 
                                            ? entry 
                                            : current
                            );
                        } finally {
                            l.unlock();
                        }

                        if ( updated == entry ) {
                            LOG.trace( "Updated entry for {}", email );
                        } else {
                            LOG.warn( "Update conflict for {}", email );
                        }

                        return updated;

                    } )
                    .doOnSubscribe( s -> LOG.trace( 
                            "Updating entry for {} ({})", 
                            email, clock.millis() 
                    ) )
                    .checkpoint( "Cache update" )
                    .cache(); // Make sure it can only be executed once
            
        }

        @Override
        public boolean valid() {

            return cached != null && staleOn.isAfter( clock.instant() );

        }

        /**
         * Whether the entry is currently expired.
         *
         * @return {@code true} if the entry is expired,
         *         {@code false} otherwise.
         */
        @SideEffectFree
        public boolean expired() {

            return expiredOn.isAfter( clock.instant() );

        }

        @Override
        public @Nullable List<DirectoryGroup> value() {

            lastAccessed.set( clock.instant() );

            // Treat as empty if past clear time
            return expired() ? cached : null;

        }

        @Override
        public Mono<List<DirectoryGroup>> latest() {

            // Call next() until finding a valid entry
            // Doing the expansion to follow the chain in case this is an old entry that got stored
            // somewhere and multiple other refreshes already happened in the meantime
            // Can't shortcut the call if this entry is already valid, as it would happen
            // when this method is called rather than when the mono is subscribed to
            return Mono.just( this )
                    .expand( e -> e.valid() ? Mono.empty() : e.next )
                    .last()
                    .doOnNext( e -> e.lastAccessed.set( clock.instant() ) )
                    .<List<DirectoryGroup>>map( e -> NullnessUtil.castNonNull( e.cached ) );

        }

        /**
         * @implSpec Compares {@link #lastAccessed last accessed time}.
         */
        @Override
        public int compareTo( final EntryImpl o ) {

            return lastAccessed.get().compareTo( o.lastAccessed.get() );

        }

    }
    
}
