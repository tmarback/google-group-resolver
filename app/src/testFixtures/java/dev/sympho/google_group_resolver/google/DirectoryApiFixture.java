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
    public static final List<Map.Entry<String, List<DirectoryGroup>>> GROUP_MAPPINGS = List.of(
            Map.entry( "foo@org.com", List.of(
                new DirectoryGroup( "Group A", "group-A@org.com" ),
                new DirectoryGroup( "Group B", "group-B@org.com" ),
                new DirectoryGroup( "Group C", "group-C@org.com" )
            ) ),
            Map.entry( "group-A@org.com", List.of(
                new DirectoryGroup( "Group A-1", "group-A-1@org.com" ),
                new DirectoryGroup( "Group A-2", "group-A-2@org.com" ),
                new DirectoryGroup( "Group A-3", "group-A-3@org.com" ),
                new DirectoryGroup( "Group A-4", "group-A-4@org.com" ),
                new DirectoryGroup( "Group A-5", "group-A-5@org.com" ),
                new DirectoryGroup( "Group A-6", "group-A-6@org.com" ),
                new DirectoryGroup( "Group A-7", "group-A-7@org.com" ),
                new DirectoryGroup( "Group A-8", "group-A-8@org.com" ),
                new DirectoryGroup( "Group A-9", "group-A-9@org.com" )
            ) ),
            Map.entry( "group-C@org.com", List.of(
                new DirectoryGroup( "Group C-1", "group-C-1@org.com" ),
                new DirectoryGroup( "Group C-2", "group-C-2@org.com" ),
                new DirectoryGroup( "Group C-3", "group-C-3@org.com" )
            ) ),
            Map.entry( "group-D@org.com", List.of(
                new DirectoryGroup( "Group A-1", "group-D-1@org.com" ),
                new DirectoryGroup( "Group A-2", "group-D-2@org.com" ),
                new DirectoryGroup( "Group A-3", "group-D-3@org.com" )
            ) ),
            Map.entry( "group-A-1@org.com", List.of(
                new DirectoryGroup( "Group A-1-1", "group-A-1-1@org.com" ),
                new DirectoryGroup( "Group A-1-2", "group-A-1-2@org.com" ),
                new DirectoryGroup( "Group A-1-3", "group-A-1-3@org.com" )
            ) ),
            Map.entry( "group-A-2@org.com", List.of(
                new DirectoryGroup( "Group A-2-1", "group-A-2-1@org.com" ),
                new DirectoryGroup( "Group A-2-2", "group-A-2-2@org.com" ),
                new DirectoryGroup( "Group A-2-3", "group-A-2-3@org.com" ),
                new DirectoryGroup( "Group A-2-4", "group-A-2-4@org.com" ),
                new DirectoryGroup( "Group A-2-5", "group-A-2-5@org.com" ),
                new DirectoryGroup( "Group A-2-6", "group-A-2-6@org.com" ),
                new DirectoryGroup( "Group A-2-7", "group-A-2-7@org.com" ),
                new DirectoryGroup( "Group A-2-8", "group-A-2-8@org.com" ),
                new DirectoryGroup( "Group A-2-9", "group-A-2-9@org.com" )
            ) )
    );

    /** Mappings from email to groups. */
    public static final SequencedMap<String, List<DirectoryGroup>> GROUP_MAP;

    static {

        final var map = LinkedHashMap.<String, List<DirectoryGroup>>newLinkedHashMap( 
                GROUP_MAPPINGS.size() 
        );
        GROUP_MAPPINGS.forEach( e -> map.put( e.getKey(), e.getValue() ) );
        GROUP_MAP = Collections.unmodifiableSequencedMap( map );

    }

    /** List of groups. */
    public static final List<DirectoryGroup> GROUP_LIST;
    
    static {
        
        final var seen = new HashSet<String>();
        GROUP_LIST = GROUP_MAPPINGS.stream()
                .map( Map.Entry::getValue )
                .flatMap( l -> l.stream() )
                .filter( g -> seen.add( g.email() ) )
                .toList();

    }

    /** Resolved groups (including indirect groups). */
    public static final SequencedMap<String, List<DirectoryGroup>> RESOLVED_GROUPS;

    /** Depth of group resolution (number of levels in the membership tree for a given email). */
    public static final SequencedMap<String, Integer> RESOLVED_GROUP_DEPTH;

    static {

        final var map = LinkedHashMap.<String, List<DirectoryGroup>>newLinkedHashMap( 
                GROUP_MAPPINGS.size() 
        );
        final var depth = LinkedHashMap.<String, Integer>newLinkedHashMap( 
                GROUP_MAPPINGS.size() 
        );
        GROUP_MAPPINGS.forEach( entry -> {

            final var email = entry.getKey();
            
            final var resolved = new ArrayList<>( entry.getValue() );
            final var resolvedEmails = resolved.stream()
                    .map( DirectoryGroup::email )
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

                final var nested = GROUP_MAP.get( nextEmail );
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
                        .map( DirectoryGroup::email )
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

        return new DirectoryApiMock( GROUP_MAP, ERROR_QUERIES );

    }
    
}
