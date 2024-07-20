package dev.sympho.google_group_resolver.google;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

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
