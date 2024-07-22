package dev.sympho.google_group_resolver;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import reactor.core.publisher.Mono;

/**
 * Endpoints for group resolution.
 */
@Controller
public class ResolveController {

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( ResolveController.class );

    /** The backing resolver. */
    private final GroupResolver resolver;

    /**
     * Creates a new instance.
     *
     * @param resolver The resolver to use.
     */
    public ResolveController( final GroupResolver resolver ) {

        this.resolver = resolver;

    }

    /**
     * Resolves the group memberships of the given entity.
     *
     * @param email The email that identifies the entity to resolve.
     * @return The response.
     */
    @GetMapping( "/resolve" )
    @ResponseBody
    public Mono<Response> resolve( final @RequestParam String email ) {

        LOG.trace( "Requested resolution for {}", email );
        return resolver.getGroupsFor( email )
                .collectList()
                .map( groups -> new Response( groups ) );

    }
    
    /**
     * Resolution response.
     *
     * @param groups The groups that the entity is a member of.
     */
    public record Response(
            List<DirectoryGroup> groups
    ) {}
    
}
