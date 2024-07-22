package dev.sympho.google_group_resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import dev.sympho.google_group_resolver.google.DirectoryService;
import reactor.core.publisher.Flux;
import reactor.scheduler.clock.SchedulerClock;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * Base for {@link GroupCache} implementations.
 *
 * @param <T> The implementation type.
 */
@ExtendWith( MockitoExtension.class )
@Timeout( 5 )
@ResourceLock( CustomResourceLocks.SCHEDULERS ) // Scheduler set up globally
public abstract class GroupCacheTest<T extends GroupCache> {

    /** The directory service. */
    @Mock
    DirectoryService directory;

    /** The virtual scheduler to use. */
    VirtualTimeScheduler scheduler;

    /** The clock to use. */
    Clock clock;

    /** The instance being tested. */
    T iut;

    /**
     * Sets up test instances.
     */
    @BeforeEach
    public void setUp() {

        scheduler = VirtualTimeScheduler.getOrSet( true );

        clock = SchedulerClock.of( scheduler );

        iut = makeIUT();

    }

    /**
     * Stops running test services.
     */
    @AfterEach
    public void tearDown() {

        VirtualTimeScheduler.reset();

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
    public void testFetchOneOnce() {

        final var email = "test@foo.bar";
        final var groups = List.of( 
                new DirectoryGroup( "A", "a@foo.bar" ), 
                new DirectoryGroup( "B", "b@foo.bar" ), 
                new DirectoryGroup( "C", "c@foo.bar" )
        );

        final var delay = Duration.ofSeconds( 1 );

        StepVerifier.withVirtualTime( () -> {

            Mockito.when( directory.getGroups( email ) )
                    .thenReturn( Flux.fromIterable( groups ).delaySubscription( delay ) );

            final var entry = iut.get( email );

            assertThat( entry.valid() ).isFalse();
            assertThat( entry.value() ).isNull();

            return entry.latest().doOnSuccess( s -> assertThat( iut.size() )
                    .isEqualTo( Math.min( 1, iut.capacity() ) ) 
            );

        }, () -> scheduler, Long.MAX_VALUE )
                .expectSubscription()
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .verifyComplete();

    }

    /**
     * Tests fetching many entries one time.
     */
    @Test
    public void testFetchManyOnce() {

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

        final var delay = Duration.ofSeconds( 1 );

        final var verifier = StepVerifier.withVirtualTime( () -> {

            for ( final var entry : cases ) {

                final var email = entry.getKey();
                final var groups = entry.getValue();

                Mockito.when( directory.getGroups( email ) )
                        .thenReturn( Flux.fromIterable( groups ).delaySubscription( delay ) );

            }

            final var entries = cases.stream()
                    .map( e -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest();

                    } ).toList();

            return Flux.fromIterable( entries ).flatMap( e -> e )
                    .doOnComplete( () -> assertThat( iut.size() )
                            .isEqualTo( Math.min( cases.size(), iut.capacity() ) ) 
                    );

        }, () -> scheduler, Long.MAX_VALUE )
                .expectSubscription()
                .expectNoEvent( delay );

        for ( final var entry : cases ) {

            verifier.assertNext( result -> assertThat( result )
                    .containsExactlyInAnyOrderElementsOf( entry.getValue() ) 
            );

        }
        
        verifier.verifyComplete();

    }
    
}
