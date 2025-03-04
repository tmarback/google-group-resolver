package dev.sympho.google_group_resolver;

import java.time.Duration;
import java.util.Objects;

import org.checkerframework.dataflow.qual.Pure;

import dev.sympho.google_group_resolver.google.DirectoryService;
import reactor.core.publisher.Mono;

/**
 * "Cache" that does not store data, but rather just transparently fetches new data every time.
 * 
 * <p>Entries provided by this implementation are never valid and never have a value.
 */
public class PassthroughGroupCache implements GroupCache {

    /** Client to use to fetch new data. */
    private final DirectoryService directory;

    /** How long after being fetched that data becomes stale. */
    private final Duration ttlLive;

    /**
     * Creates a new instance.
     *
     * @param directory Client to use to fetch new data.
     * @param ttlLive How long after being fetched that data should become stale.
     */
    @Pure
    public PassthroughGroupCache( 
        final DirectoryService directory,
        final Duration ttlLive
    ) {

        this.directory = Objects.requireNonNull( directory );
        this.ttlLive = ttlLive;

    }

    @Override
    public Mono<Entry> get( final String email ) {

        return update( email );

    }

    @Override
    public Mono<Entry> update( final String email ) {

        return directory.getGroupsFor( email )
            .collectList()
            .map( gs -> LoadedEntry.of( gs, ttlLive ) );

    }

    @Override
    public long size() {
        return 0;
    }

    @Override
    public long capacity() {
        return 0;
    }
    
}
