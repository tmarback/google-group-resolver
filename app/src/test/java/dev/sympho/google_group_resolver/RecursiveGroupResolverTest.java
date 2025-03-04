package dev.sympho.google_group_resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link RecursiveGroupResolver}.
 */
@ExtendWith( MockitoExtension.class )
@Timeout( 5 )
public class RecursiveGroupResolverTest {

    /** The target email to query. */
    private static final String TARGET_EMAIL = "my-email@foo.bar";

    /** The mappings to test for. */
    private static final List<Map.Entry<String, List<DirectoryGroup>>> MAPPINGS = List.of(
        Map.entry( TARGET_EMAIL, List.of(
            new DirectoryGroup( "test-1", "test-1@foo.bar" ),
            new DirectoryGroup( "test-2", "test-2@foo.bar" ),
            new DirectoryGroup( "test-3", "test-3@foo.bar" )
        ) ),
        Map.entry( "test-1@foo.bar", List.of(
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "C", "c@foo.bar" )
        ) ),
        Map.entry( "test-2@foo.bar", List.of(
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "D", "d@foo.bar" ), 
            new DirectoryGroup( "E", "e@foo.bar" )
        ) ),
        Map.entry( "test-3@foo.bar", List.of(
            new DirectoryGroup( "F", "f@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "G", "g@foo.bar" )
        ) ),
        Map.entry( "a@foo.bar", List.of(
            new DirectoryGroup( "1", "1@foo.bar" ), 
            new DirectoryGroup( "2", "2@foo.bar" )
        ) )
    );

    /** Expected results from the query. */
    private static final Set<DirectoryGroup> EXPECTED = MAPPINGS.stream()
        .flatMap( e -> e.getValue().stream() )
        .collect( Collectors.toSet() );

    /** 
     * A mock cache entry. 
     *
     * @param valid Whether the entry is valid.
     * @param value The entry value.
     */
    private record MockEntry(
        boolean valid,
        List<DirectoryGroup> value
    ) implements GroupCache.Entry {}

    /**
     * The tests to run.
     */
    private abstract class Base {

        /** The cache mock. */
        @Mock
        protected GroupCache cache;

        /** The instance to test. */
        RecursiveGroupResolver iut;

        /**
         * Whether to enable prefetching.
         *
         * @return If {@code true}, the IUT will have prefetching enabled.
         */
        protected abstract boolean prefetch();

        /**
         * Sets up the test environment.
         */
        @BeforeEach
        public void setUp() {

            iut = new RecursiveGroupResolver( cache, prefetch(), ObservationRegistry.NOOP );

            final var defaultEntry = new MockEntry( false, Collections.emptyList() );
            Mockito.when( cache.get( anyString() ) )
                .thenReturn( Mono.just( defaultEntry ) );
            Mockito.when( cache.update( anyString() ) )
                .thenReturn( Mono.just( defaultEntry ) );

        }

        /**
         * Checks that the IUT gives the correct result.
         */
        private void checkResult() {

            final var result = iut.getGroupsFor( TARGET_EMAIL )
                .collectList()
                .block();

            assertThat( result ).containsExactlyInAnyOrderElementsOf( EXPECTED );

        }

        /**
         * Tests a query with no entries cached.
         */
        @Test
        public void testNotCached() {

            for ( final var entry : MAPPINGS ) {

                final var email = entry.getKey();
                final var groups = entry.getValue();

                Mockito.lenient().when( cache.update( email ) )
                    .thenReturn( Mono.just( new MockEntry( true, groups ) ) );

            }

            checkResult();

        }

        /**
         * Tests a query with cached entries.
         */
        @Test
        public void testCached() {

            for ( final var entry : MAPPINGS ) {

                final var email = entry.getKey();
                final var groups = entry.getValue();

                Mockito.lenient().when( cache.get( email ) )
                    .thenReturn( Mono.just( new MockEntry( true, groups ) ) );

            }

            checkResult();

        }

        /**
         * Tests a query with cached but stale entries.
         */
        @Test
        public void testCachedStale() {

            final var staleMappings = List.of(
                Map.entry( TARGET_EMAIL, List.of(
                    new DirectoryGroup( "test-1", "test-1@foo.bar" ),
                    new DirectoryGroup( "test-2", "test-2@foo.bar" ),
                    new DirectoryGroup( "test-3", "test-3@foo.bar" ),
                    new DirectoryGroup( "test-4", "test-4@foo.bar" )
                ) ),
                Map.entry( "test-1@foo.bar", List.of(
                    new DirectoryGroup( "A", "a@foo.bar" ), 
                    new DirectoryGroup( "B", "b@foo.bar" )
                ) ),
                Map.entry( "test-2@foo.bar", List.of(
                    new DirectoryGroup( "A", "a@foo.bar" ), 
                    new DirectoryGroup( "D", "d@foo.bar" ), 
                    new DirectoryGroup( "E", "e@foo.bar" )
                ) ),
                Map.entry( "test-3@foo.bar", List.of(
                    new DirectoryGroup( "M", "m@foo.bar" ), 
                    new DirectoryGroup( "N", "n@foo.bar" ), 
                    new DirectoryGroup( "O", "o@foo.bar" )
                ) ),
                Map.entry( "test-4@foo.bar", List.of(
                    new DirectoryGroup( "F", "f@foo.bar" ), 
                    new DirectoryGroup( "Z", "z@foo.bar" )
                ) ),
                Map.entry( "a@foo.bar", List.of(
                    new DirectoryGroup( "1", "1@foo.bar" ), 
                    new DirectoryGroup( "2", "2@foo.bar" )
                ) )
            );

            for ( final var entry : staleMappings ) {

                final var email = entry.getKey();
                final var groups = entry.getValue();

                Mockito.lenient().when( cache.get( email ) )
                    .thenReturn( Mono.just( new MockEntry( false, groups ) ) );

            }

            for ( final var entry : MAPPINGS ) {

                final var email = entry.getKey();
                final var groups = entry.getValue();

                Mockito.lenient().when( cache.update( email ) )
                    .thenReturn( Mono.just( new MockEntry( true, groups ) ) );

            }

            checkResult();

        }

    }

    /**
     * Tests the resolver with prefetch disabled.
     */
    @Nested
    public class WithoutPrefetch extends Base {

        @Override
        protected boolean prefetch() {
            return false;
        }

    }

    /**
     * Tests the resolver with prefetch enabled.
     */
    @Nested
    public class WithPrefetch extends Base {

        @Override
        protected boolean prefetch() {
            return true;
        }

    }
    
}
