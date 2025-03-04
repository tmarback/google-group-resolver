package dev.sympho.google_group_resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import dev.sympho.google_group_resolver.google.DirectoryService;
import reactor.core.publisher.Flux;

/**
 * Base for {@link GroupCache} implementations.
 *
 * @param <T> The implementation type.
 */
@ExtendWith( MockitoExtension.class )
@Timeout( 5 )
public abstract class GroupCacheTest<T extends GroupCache> {

    /** The time an entry stays in valid status. */
    protected static final Duration TTL_VALID = Duration.ofMillis( 500 );

    /** The directory service. */
    @Mock
    DirectoryService directory;

    /** The instance being tested. */
    T iut;

    /**
     * Sets up test instances.
     */
    @BeforeEach
    public void setUp() {

        iut = makeIUT();

    }

    /**
     * Creates the instance to test.
     *
     * @return The created instance.
     */
    protected abstract T makeIUT();

    /**
     * Tests fetching a single entry one time.
     */
    @Test
    public void testGetOneOnce() {

        final var email = "test@foo.bar";
        final var groups = List.of( 
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "C", "c@foo.bar" )
        );

        Mockito.when( directory.getGroupsFor( email ) )
                .thenReturn( Flux.fromIterable( groups ) );

        final var result = iut.get( email ).block();
        assertThat( result.valid() ).isTrue();
        assertThat( result.value() )
            .containsExactlyInAnyOrderElementsOf( groups );

        assertThat( iut.size() )
            .isEqualTo( Math.min( 1, iut.capacity() ) );

    }

    /**
     * Tests fetching the same entry multiple times.
     */
    @Test
    public void testGetOneRepeat() {

        final var email = "test@foo.bar";
        final var groups = List.of( 
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "C", "c@foo.bar" )
        );

        Mockito.when( directory.getGroupsFor( email ) )
                .thenReturn( Flux.fromIterable( groups ) );

        for ( int i = 0; i < 4; i++ ) {
            final var result = iut.get( email ).block();
            assertThat( result.valid() ).isTrue();
            assertThat( result.value() )
                .containsExactlyInAnyOrderElementsOf( groups );
        }

        assertThat( iut.size() )
            .isEqualTo( Math.min( 1, iut.capacity() ) );

    }

    /**
     * Tests fetching many entries one time.
     */
    @Test
    public void testGetManyOnce() {

        final var cases = List.of(
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
            ) )
        );

        for ( final var entry : cases ) {

            final var email = entry.getKey();
            final var groups = entry.getValue();

            Mockito.when( directory.getGroupsFor( email ) )
                .thenReturn( Flux.fromIterable( groups ) );

        }

        for ( final var entry : cases ) {

            final var email = entry.getKey();
            final var groups = entry.getValue();

            final var result = iut.get( email ).block();
            assertThat( result.valid() ).isTrue();
            assertThat( result.value() )
                .containsExactlyInAnyOrderElementsOf( groups );

        }

        assertThat( iut.size() )
            .isEqualTo( Math.min( cases.size(), iut.capacity() ) );

    }

    /**
     * Tests fetching many entries multiple times.
     */
    @Test
    public void testGetManyRepeat() {

        final var cases = List.of(
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
            ) )
        );

        for ( final var entry : cases ) {

            final var email = entry.getKey();
            final var groups = entry.getValue();

            Mockito.when( directory.getGroupsFor( email ) )
                .thenReturn( Flux.fromIterable( groups ) );

        }

        for ( int i = 0; i < 5; i++ ) {
            for ( final var entry : cases ) {

                final var email = entry.getKey();
                final var groups = entry.getValue();

                final var result = iut.get( email ).block();
                assertThat( result.valid() ).isTrue();
                assertThat( result.value() )
                    .containsExactlyInAnyOrderElementsOf( groups );

            }
        }

        assertThat( iut.size() )
            .isEqualTo( Math.min( cases.size(), iut.capacity() ) );

    }

    /**
     * Tests that an entry is marked as not valid after expiring.
     */
    @Test
    public void testValidMarker() throws InterruptedException {

        final var email = "test@foo.bar";
        final var groups = List.of( 
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "C", "c@foo.bar" )
        );

        Mockito.when( directory.getGroupsFor( email ) )
                .thenReturn( Flux.fromIterable( groups ) );

        final var result = iut.get( email ).block();
        assertThat( result.valid() ).isTrue();
        assertThat( result.value() )
            .containsExactlyInAnyOrderElementsOf( groups );

        Thread.sleep( TTL_VALID );

        assertThat( result.valid() ).isFalse();

        assertThat( iut.size() )
            .isEqualTo( Math.min( 1, iut.capacity() ) );

    }

    /**
     * Tests that the cached value of an entry is updated 
     */
    @Test
    public void testEntryUpdate() throws InterruptedException {

        final var email = "test@foo.bar";
        final var groups = List.of( 
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "C", "c@foo.bar" )
        );
        final var newGroups = List.of( 
            new DirectoryGroup( "D", "d@foo.bar" ), 
            new DirectoryGroup( "E", "e@foo.bar" ), 
            new DirectoryGroup( "F", "f@foo.bar" )
        );

        Mockito.when( directory.getGroupsFor( email ) )
                .thenReturn( Flux.fromIterable( groups ) );

        for ( int i = 0; i < 2; i++ ) {
            final var result = iut.update( email ).block();
            assertThat( result.valid() ).isTrue();
            assertThat( result.value() )
                .containsExactlyInAnyOrderElementsOf( groups );
        }

        Thread.sleep( TTL_VALID );

        Mockito.when( directory.getGroupsFor( email ) )
                .thenReturn( Flux.fromIterable( newGroups ) );

        for ( int i = 0; i < 2; i++ ) {
            final var result = iut.update( email ).block();
            assertThat( result.valid() ).isTrue();
            assertThat( result.value() )
                .containsExactlyInAnyOrderElementsOf( newGroups );
        }

        assertThat( iut.size() )
            .isEqualTo( Math.min( 1, iut.capacity() ) );

    }
    
}
