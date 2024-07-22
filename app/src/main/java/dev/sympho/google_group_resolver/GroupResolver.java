package dev.sympho.google_group_resolver;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import reactor.core.publisher.Flux;

/**
 * Resolver that determines what groups a given entity is member of.
 */
public interface GroupResolver {

    /**
     * Determines which groups that the entity identified by the given email is a member of.
     *
     * @param email The email of the entity to resolve.
     * @return The groups that the given entity is a member of.
     * @implSpec The returned flux may <b>not</b> issue multiple groups with the same 
     *           {@link DirectoryGroup#email() email} (i.e. each issued group must have a unique 
     *           email). In the case that the backing dataset contains duplicate emails 
     *           (particularly in the case that resolution queries a group more than once but its 
     *           data is updated in between those queries), the selection of which to issue is not 
     *           defined.
     */
    Flux<DirectoryGroup> getGroupsFor( String email );
    
}
