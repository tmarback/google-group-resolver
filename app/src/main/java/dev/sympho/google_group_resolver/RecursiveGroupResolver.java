package dev.sympho.google_group_resolver;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.checkerframework.dataflow.qual.Pure;

import dev.sympho.google_group_resolver.google.DirectoryService.Group;
import reactor.core.publisher.Flux;

/**
 * Group resolver that includes indirect group memberships.
 */
public class RecursiveGroupResolver implements GroupResolver {

    /** The cache to fetch from. */
    private final GroupCache cache;

    /** Whether to prefetch using stale data. */
    private final boolean prefetch;

    /**
     * Creates a new instance.
     *
     * @param cache The cache to fetch from.
     * @param prefetch If {@code true}, 
     */
    @Pure
    public RecursiveGroupResolver( final GroupCache cache, final boolean prefetch ) {

        this.cache = cache;
        this.prefetch = prefetch;

    }

    /**
     * Retrieves the indirect groups from a list of direct groups.
     *
     * @param groups The direct groups.
     * @param seen The groups that were already visited and should be skipped if seen again.
     * @return The indirect groups.
     */
    private Flux<Group> getIndirectGroups( final List<Group> groups, final Set<String> seen ) {

        final var updatedSeen = new HashSet<>( seen );
        final var newGroups = groups.stream()
                .map( Group::email )
                .filter( updatedSeen::add )
                .toList();

        final var seenArg = Collections.unmodifiableSet( updatedSeen );
        return Flux.fromIterable( newGroups ).flatMap( email -> getGroupsFor( email, seenArg ) );

    }

    /**
     * Retrieves the groups (direct or indirect) for the entity identified by the given email.
     *
     * @param email The email of the entity to resolve.
     * @param seen The groups that were already visited and should be skipped if seen again.
     * @return The indirect groups.
     */
    private Flux<Group> getGroupsFor( final String email, final Set<String> seen ) {

        final var entry = cache.get( email );
        
        if ( prefetch && !entry.valid() ) {
            final var cached = entry.value();
            if ( cached != null ) { // Stale but non-expired cache value, use for prefetch
                // Don't need to wait for the prefetch to finish, just let it run in the background
                getIndirectGroups( cached, seen ).subscribe();
            }
        }

        return entry.latest()
                .filter( g -> !g.isEmpty() ) // Skip processing nested if empty
                .flatMapMany( groups -> Flux.fromIterable( groups )
                        .mergeWith( getIndirectGroups( groups, seen ) )
                );

    }

    @Override
    public Flux<Group> getGroupsFor( final String email ) {

        return getGroupsFor( Objects.requireNonNull( email ), Set.of( email ) )
                .distinct( Group::email ); // Don't allow duplicate emails through

    }
    
}
