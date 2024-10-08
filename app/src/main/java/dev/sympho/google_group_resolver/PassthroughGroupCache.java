package dev.sympho.google_group_resolver;

import java.util.List;
import java.util.Objects;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
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

    /**
     * Creates a new instance.
     *
     * @param directory Client to use to fetch new data.
     */
    @Pure
    public PassthroughGroupCache( final DirectoryService directory ) {

        this.directory = Objects.requireNonNull( directory );

    }

    @Override
    public Entry get( final String email ) {

        return new Entry() {

            @Override
            public boolean valid() {
                return false;
            }

            @Override
            public @Nullable List<DirectoryGroup> value() {
                return null;
            }

            @Override
            public Mono<List<DirectoryGroup>> latest() {
                return directory.getGroupsFor( email ).collectList();
            }

        };

    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public int capacity() {
        return 0;
    }
    
}
