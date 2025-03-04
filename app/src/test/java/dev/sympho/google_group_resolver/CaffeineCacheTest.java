package dev.sympho.google_group_resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link CaffeineGroupCache}.
 */
public class CaffeineCacheTest extends GroupCacheTest<CaffeineGroupCache> {

    /** The time an entry stays in stale status. */
    private static final Duration TTL_STALE = Duration.ofMillis( 500 );

    /** The cache capacity. */
    private static final int CAPACITY = 10;

    @Override
    protected CaffeineGroupCache makeIUT() {

        return new CaffeineGroupCache( 
            directory, 
            TTL_VALID, 
            TTL_STALE, 
            CAPACITY,
            new CompositeMeterRegistry(),
            ObservationRegistry.NOOP
        );

    }

    /**
     * Tests one expired entry being cleaned up in an otherwise empty cache.
     */
    @Test
    public void testExpiredCleanupOneRemainNone() throws InterruptedException {

        final var email = "test@foo.bar";
        final var groups = List.of( 
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "C", "c@foo.bar" )
        );

        Mockito.when( directory.getGroupsFor( email ) )
            .thenAnswer( inv -> Flux.fromIterable( groups ) );

        final var result = iut.get( email ).block();
        assertThat( result.valid() ).isTrue();
        assertThat( result.value() )
            .containsExactlyInAnyOrderElementsOf( groups );

        Thread.sleep( TTL_VALID );
        Thread.sleep( TTL_STALE );

        iut.cleanUp();
        assertThat( iut.size() )
            .isEqualTo( 0 );

    }

    /**
     * Tests one expired entry being cleaned up with other entries remaining.
     */
    @Test
    public void testExpiredCleanupOneRemainMany() throws InterruptedException {

        final var email = "test@foo.bar";
        final var groups = List.of( 
            new DirectoryGroup( "A", "a@foo.bar" ), 
            new DirectoryGroup( "B", "b@foo.bar" ), 
            new DirectoryGroup( "C", "c@foo.bar" )
        );

        final var extras = List.of(
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

        Mockito.when( directory.getGroupsFor( email ) )
            .thenAnswer( inv -> Flux.fromIterable( groups ) );

        for ( final var entry : extras ) {

            final var extraEmail = entry.getKey();
            final var extraGroups = entry.getValue();

            Mockito.when( directory.getGroupsFor( extraEmail ) )
                .thenReturn( Flux.fromIterable( extraGroups ) );

        }

        final var result = iut.get( email ).block();
        assertThat( result.valid() ).isTrue();
        assertThat( result.value() )
            .containsExactlyInAnyOrderElementsOf( groups );

        Thread.sleep( TTL_VALID );

        for ( final var entry : extras ) {

            final var extraEmail = entry.getKey();
            final var extraGroups = entry.getValue();

            final var extraResult = iut.get( extraEmail ).block();
            assertThat( extraResult.valid() ).isTrue();
            assertThat( extraResult.value() )
                .containsExactlyInAnyOrderElementsOf( extraGroups );

        }

        iut.cleanUp();
        assertThat( iut.size() )
            .isEqualTo( extras.size() + 1 );

        Thread.sleep( TTL_STALE );

        iut.cleanUp();
        assertThat( iut.size() )
            .isEqualTo( extras.size() );

    }
    
}
