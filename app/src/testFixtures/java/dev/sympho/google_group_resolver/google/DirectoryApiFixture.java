package dev.sympho.google_group_resolver.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import reactor.util.function.Tuples;

/**
 * Fixture for testing features dependent on API functionality.
 * 
 * <p>This is to avoid needing to fully define a group hierarchy for many tests.
 */
public final class DirectoryApiFixture {

    /** Emails to generate errors for when queried. */
    public static final List<String> ERROR_QUERIES = List.of(
            "error-1@foo.bar",
            "error-2@foo.bar",
            "error-3@foo.bar"
    );
    
    /** Group hierarchy for testing. */
    public static final List<Map.Entry<String, List<DirectoryApi.Group>>> API_GROUP_LIST = List.of(
            Map.entry( "foo@org.com", List.of(
                new DirectoryApi.Group( "Group A", "group-A@org.com" ),
                new DirectoryApi.Group( "Group B", "group-B@org.com" ),
                new DirectoryApi.Group( "Group C", "group-C@org.com" )
            ) ),
            Map.entry( "group-A@org.com", List.of(
                new DirectoryApi.Group( "Group A-1", "group-A-1@org.com" ),
                new DirectoryApi.Group( "Group A-2", "group-A-2@org.com" ),
                new DirectoryApi.Group( "Group A-3", "group-A-3@org.com" ),
                new DirectoryApi.Group( "Group A-4", "group-A-4@org.com" ),
                new DirectoryApi.Group( "Group A-5", "group-A-5@org.com" ),
                new DirectoryApi.Group( "Group A-6", "group-A-6@org.com" ),
                new DirectoryApi.Group( "Group A-7", "group-A-7@org.com" ),
                new DirectoryApi.Group( "Group A-8", "group-A-8@org.com" ),
                new DirectoryApi.Group( "Group A-9", "group-A-9@org.com" )
            ) ),
            Map.entry( "group-C@org.com", List.of(
                new DirectoryApi.Group( "Group C-1", "group-C-1@org.com" ),
                new DirectoryApi.Group( "Group C-2", "group-C-2@org.com" ),
                new DirectoryApi.Group( "Group C-3", "group-C-3@org.com" )
            ) ),
            Map.entry( "group-D@org.com", List.of(
                new DirectoryApi.Group( "Group A-1", "group-D-1@org.com" ),
                new DirectoryApi.Group( "Group A-2", "group-D-2@org.com" ),
                new DirectoryApi.Group( "Group A-3", "group-D-3@org.com" )
            ) ),
            Map.entry( "group-A-1@org.com", List.of(
                new DirectoryApi.Group( "Group A-1-1", "group-A-1-1@org.com" ),
                new DirectoryApi.Group( "Group A-1-2", "group-A-1-2@org.com" ),
                new DirectoryApi.Group( "Group A-1-3", "group-A-1-3@org.com" )
            ) ),
            Map.entry( "group-A-2@org.com", List.of(
                new DirectoryApi.Group( "Group A-2-1", "group-A-2-1@org.com" ),
                new DirectoryApi.Group( "Group A-2-2", "group-A-2-2@org.com" ),
                new DirectoryApi.Group( "Group A-2-3", "group-A-2-3@org.com" ),
                new DirectoryApi.Group( "Group A-2-4", "group-A-2-4@org.com" ),
                new DirectoryApi.Group( "Group A-2-5", "group-A-2-5@org.com" ),
                new DirectoryApi.Group( "Group A-2-6", "group-A-2-6@org.com" ),
                new DirectoryApi.Group( "Group A-2-7", "group-A-2-7@org.com" ),
                new DirectoryApi.Group( "Group A-2-8", "group-A-2-8@org.com" ),
                new DirectoryApi.Group( "Group A-2-9", "group-A-2-9@org.com" )
            ) )
    );

    /** Mappings from email to groups. */
    public static final SequencedMap<String, List<DirectoryApi.Group>> API_GROUP_MAP;

    static {

        final var map = LinkedHashMap.<String, List<DirectoryApi.Group>>newLinkedHashMap( 
                API_GROUP_LIST.size() 
        );
        API_GROUP_LIST.forEach( e -> map.put( e.getKey(), e.getValue() ) );
        API_GROUP_MAP = Collections.unmodifiableSequencedMap( map );

    }

    /** Group hierarchy for testing. */
    public static final List<Map.Entry<String, List<DirectoryService.Group>>> SERVICE_GROUP_LIST;

    static {

        SERVICE_GROUP_LIST = API_GROUP_LIST.stream().map( e -> Map.entry( 
                e.getKey(), 
                e.getValue().stream()
                        .map( group -> new DirectoryService.Group(
                                group.name(),
                                group.email()
                        ) )
                        .toList()
        ) ).toList();

    }

    /** Mappings from email to groups. */
    public static final SequencedMap<String, List<DirectoryService.Group>> SERVICE_GROUP_MAP;

    static {

        final var map = LinkedHashMap.<String, List<DirectoryService.Group>>newLinkedHashMap( 
                SERVICE_GROUP_LIST.size() 
        );
        SERVICE_GROUP_LIST.forEach( e -> map.put( e.getKey(), e.getValue() ) );
        SERVICE_GROUP_MAP = Collections.unmodifiableSequencedMap( map );

    }

    /** Resolved groups (including indirect groups). */
    public static final SequencedMap<String, List<DirectoryService.Group>> RESOLVED_GROUPS;

    /** Depth of group resolution (number of levels in the membership tree for a given email). */
    public static final SequencedMap<String, Integer> RESOLVED_GROUP_DEPTH;

    static {

        final var map = LinkedHashMap.<String, List<DirectoryService.Group>>newLinkedHashMap( 
                SERVICE_GROUP_LIST.size() 
        );
        final var depth = LinkedHashMap.<String, Integer>newLinkedHashMap( 
                SERVICE_GROUP_LIST.size() 
        );
        SERVICE_GROUP_LIST.forEach( entry -> {

            final var email = entry.getKey();
            
            final var resolved = new ArrayList<>( entry.getValue() );
            final var resolvedEmails = resolved.stream()
                    .map( DirectoryService.Group::email )
                    .toList();
            final var pending = new LinkedList<>( resolvedEmails.stream()
                    .map( e -> Tuples.of( e, 1 ) )
                    .toList() 
            );
            final var seen = new HashSet<>( resolvedEmails );

            int maxDepth = 0;
            while ( !pending.isEmpty() ) {

                final var next = pending.removeFirst();
                final var nextEmail = next.getT1();
                final int nextDepth = next.getT2();

                final var nested = SERVICE_GROUP_MAP.get( nextEmail );
                if ( nested != null ) {
                    nested.stream()
                            .filter( g -> seen.add( g.email() ) )
                            .forEach( g -> {

                                resolved.add( g );
                                pending.addLast( Tuples.of( g.email(), nextDepth + 1 ) );

                            } );
                }

                maxDepth = Math.max( maxDepth, nextDepth );

            }

            map.put( email, resolved );
            depth.put( email, maxDepth );

        } );
        RESOLVED_GROUPS = Collections.unmodifiableSequencedMap( map );
        RESOLVED_GROUP_DEPTH = Collections.unmodifiableSequencedMap( depth );

    }

    /** Emails of resolved groups (including indirect groups). */
    public static final SequencedMap<String, List<String>> RESOLVED_GROUP_EMAILS;

    static {

        final var map = LinkedHashMap.<String, List<String>>newLinkedHashMap( 
                RESOLVED_GROUPS.size() 
        );
        RESOLVED_GROUPS.forEach( ( email, groups ) -> map.put( 
                email, 
                groups.stream()
                        .map( DirectoryService.Group::email )
                        .toList()
        ) );
        RESOLVED_GROUP_EMAILS = Collections.unmodifiableSequencedMap( map );

    }

    /** Do not instantiate. */
    private DirectoryApiFixture() {}

    /**
     * Constructs a mock API client with this fixture.
     *
     * @return The created client.
     */
    public static DirectoryApiMock apiClient() {

        return new DirectoryApiMock( API_GROUP_MAP, ERROR_QUERIES );

    }
    
}
