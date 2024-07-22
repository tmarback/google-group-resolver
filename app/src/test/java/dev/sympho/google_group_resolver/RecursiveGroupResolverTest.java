package dev.sympho.google_group_resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.sympho.google_group_resolver.google.DirectoryApiFixture;
import dev.sympho.google_group_resolver.google.DirectoryGroup;
import dev.sympho.google_group_resolver.google.DirectoryService;
import reactor.core.publisher.Flux;
import reactor.scheduler.clock.SchedulerClock;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * Unit tests for {@link RecursiveGroupResolver}.
 */
@ExtendWith( MockitoExtension.class )
@Timeout( 5 )
public class RecursiveGroupResolverTest {

    /**
     * Base configuration for tests.
     *
     * @param <C> The cache implementation being used.
     */
    private abstract class Base<C extends GroupCache> {

        /** Simulated query latency. */
        static final Duration DELAY = Duration.ofMillis( 100 );

        /** The backing directory service mock. */
        @Mock
        DirectoryService directory;

        /** The cache being used. */
        C cache;

        /** The instance under test. */
        RecursiveGroupResolver iut;

        /**
         * Creates and starts the cache to use.
         *
         * @param client The backing directory service.
         * @return The cache.
         */
        protected abstract C makeCache( DirectoryService client );

        /**
         * Stops the cache.
         *
         * @param c The cache.
         */
        protected abstract void stopCache( C c );

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

            cache = Objects.requireNonNull( makeCache( directory ) );

            iut = new RecursiveGroupResolver( cache, prefetch() );

        }

        /**
         * Cleans up the test environment.
         */
        @AfterEach
        public void tearDown() {

            stopCache( cache );

        }

        /**
         * Configures the directory service mock with test data.
         *
         * @param insertDelay If {@code true}, adds a {@link #DELAY simulated latency} to every
         *                    query.
         */
        protected void configureMock( final boolean insertDelay ) {

            Mockito.when( directory.getGroups( anyString() ) ).thenAnswer( invocation -> {

                final String email = invocation.getArgument( 0 );

                final var groups = DirectoryApiFixture.SERVICE_GROUP_MAP.get( email );
                final var result = groups == null ? Flux.empty() : Flux.fromIterable( groups );

                return insertDelay ? result.delaySubscription( DELAY ) : result;

            } );

        }

    }

    /**
     * Behavioral tests that all configurations should satisfy.
     *
     * @param <C> The cache implementation being used.
     */
    @ResourceLock( value = CustomResourceLocks.SCHEDULERS, mode = ResourceAccessMode.READ )
    private abstract class BehaviorTests<C extends GroupCache> extends Base<C> {

        /**
         * Configures test data before each test.
         */
        @BeforeEach
        public void configureMock() {

            configureMock( false );

        }

        /**
         * Argument provider for {@link #testResolveOne(String)}.
         *
         * @return The arguments.
         */
        private static Stream<String> testResolveOne() {

            return DirectoryApiFixture.RESOLVED_GROUPS.keySet().stream();

        }

        /**
         * Tests that the resolver can successfully resolve the groups of a single entity.
         *
         * @param email The email to query.
         */
        @ParameterizedTest
        @MethodSource
        public void testResolveOne( final String email ) {

            final var expected = DirectoryApiFixture.RESOLVED_GROUP_EMAILS.get( email );

            StepVerifier.create( iut.getGroupsFor( email )
                            .map( DirectoryGroup::email )
                            .collectList() 
                    )
                    .assertNext( groups -> assertThat( groups ) 
                            .containsExactlyInAnyOrderElementsOf( expected )
                    )
                    .verifyComplete();

        }

        /**
         * Tests that the resolver can successfully resolve the groups of multiple entities.
         */
        @Test
        public void testResolveAll() {

            for ( final var entry : DirectoryApiFixture.RESOLVED_GROUP_EMAILS.entrySet() ) {

                final var email = entry.getKey();
                final var expected = entry.getValue();

                StepVerifier.create( iut.getGroupsFor( email )
                                .map( DirectoryGroup::email )
                                .collectList() 
                        )
                        .assertNext( groups -> assertThat( groups ) 
                                .containsExactlyInAnyOrderElementsOf( expected )
                        )
                        .verifyComplete();

            }

        }

        /**
         * Tests that the resolver can successfully resolve the groups of multiple entities
         * multiple times.
         */
        @Test
        public void testResolveAllRepeat() {

            for ( int i = 0; i < 5; i++ ) {
                for ( final var entry : DirectoryApiFixture.RESOLVED_GROUP_EMAILS.entrySet() ) {

                    final var email = entry.getKey();
                    final var expected = entry.getValue();

                    StepVerifier.create( iut.getGroupsFor( email )
                                    .map( DirectoryGroup::email )
                                    .collectList() 
                            )
                            .assertNext( groups -> assertThat( groups ) 
                                    .containsExactlyInAnyOrderElementsOf( expected )
                            )
                            .verifyComplete();

                }
            }

        }

    }

    /**
     * Timing tests that are affected by the configuration.
     *
     * @param <C> The cache implementation being used.
     */
    @ResourceLock( value = CustomResourceLocks.SCHEDULERS, mode = ResourceAccessMode.READ_WRITE )
    private abstract class TimingTests<C extends GroupCache> extends Base<C> {

        /** The virtual scheduler to use. */
        VirtualTimeScheduler scheduler;

        @BeforeEach
        @Override
        public void setUp() {

            scheduler = VirtualTimeScheduler.getOrSet( true );

            super.setUp();

        }

        /**
         * Stops the task handler after each test.
         */
        @AfterEach
        @Override
        public void tearDown() {

            super.tearDown();

            VirtualTimeScheduler.reset();

        }

        /**
         * Configures test data before each test.
         */
        @BeforeEach
        public void configureMock() {

            configureMock( true );

        }

    }

    /**
     * Tests a configuration without caching.
     * 
     * <p>Since not having a cache makes the resolver effectively stateless, timing tests are not
     * necessary.
     */
    @Nested
    public class NoCache extends BehaviorTests<PassthroughGroupCache> {

        @Override
        protected PassthroughGroupCache makeCache( final DirectoryService directory ) {

            return new PassthroughGroupCache( directory );

        }

        @Override
        protected void stopCache( final PassthroughGroupCache cache ) {}

        @Override
        protected boolean prefetch() {

            return false;

        }

    }

    /**
     * Tests a configuration with caching.
     */
    public abstract class WithCache {

        /** The amount of time that a cache entry remains valid. */
        static final Duration TTL_LIVE = Duration.ofSeconds( 1 );

        /** The amount of time that a cache entry remains stale. */
        static final Duration TTL_STALE = Duration.ofMinutes( 1 );

        /** How often the cleaner task runs. */
        static final Duration CLEANER_PERIOD = Duration.ofMinutes( 10 );

        /** The cache size. */
        static final int CACHE_SIZE = 100;

        /**
         * Configures the cache.
         *
         * @param directory The directory service to use.
         * @param clock The clock to use.
         * @return The configured cache.
         */
        private LRUGroupCache makeCache( final DirectoryService directory, final Clock clock ) {

            final var cache = new LRUGroupCache( 
                    directory,
                    TTL_LIVE,
                    TTL_STALE,
                    CLEANER_PERIOD,
                    CACHE_SIZE,
                    clock
            );

            cache.startCleaner();
            return cache;

        }

        /**
         * Stops the cache.
         *
         * @param cache The configured cache.
         */
        private void stopCache( final LRUGroupCache cache ) {

            cache.stopCleaner();

        }

        /**
         * Whether to enable prefetching.
         *
         * @return Whether to enable prefetching.
         */
        protected abstract boolean prefetch();

        /**
         * Behavioral tests.
         */
        @Nested
        public class Behavior extends BehaviorTests<LRUGroupCache> {

            @Override
            protected LRUGroupCache makeCache( final DirectoryService directory ) {
                return WithCache.this.makeCache( directory, Clock.systemUTC() );
            }

            @Override
            protected void stopCache( final LRUGroupCache cache ) {
                WithCache.this.stopCache( cache );
            }

            @Override
            protected boolean prefetch() {
                return WithCache.this.prefetch();
            }

        }

        /**
         * Timing-dependent tests.
         */
        public abstract class Timing extends TimingTests<LRUGroupCache> {

            @Override
            protected LRUGroupCache makeCache( final DirectoryService directory ) {
                return WithCache.this.makeCache( directory, SchedulerClock.of( scheduler ) );
            }

            @Override
            protected void stopCache( final LRUGroupCache cache ) {
                WithCache.this.stopCache( cache );
            }

            @Override
            protected boolean prefetch() {
                return WithCache.this.prefetch();
            }

            /* Tests that are not affected by prefetching */

            /**
             * Argument provider for {@link #testCleanQuery(String)}.
             *
             * @return The arguments.
             */
            private static Stream<String> testCleanQuery() {

                return DirectoryApiFixture.RESOLVED_GROUPS.keySet().stream();
    
            }

            /**
             * Tests resolving a single entity with a clean cache.
             *
             * @param email The email to query.
             */
            @ParameterizedTest
            @MethodSource
            public void testCleanQuery( final String email ) {

                final var expected = DirectoryApiFixture.RESOLVED_GROUP_EMAILS.get( email );
                final var depth = DirectoryApiFixture.RESOLVED_GROUP_DEPTH.get( email );

                StepVerifier.withVirtualTime(
                                () -> iut.getGroupsFor( email )
                                        .map( DirectoryGroup::email )
                                        .collectList(),
                                () -> scheduler,
                                Long.MAX_VALUE
                        )
                        .expectSubscription()
                        .expectNoEvent( DELAY.multipliedBy( 1 + depth ) )
                        .assertNext( groups -> assertThat( groups ) 
                                .containsExactlyInAnyOrderElementsOf( expected )
                        )
                        .verifyComplete();

            }

            /**
             * Tests resolving entities that are fully cached.
             */
            @Test
            public void testQueryCached() {

                final var verifier = StepVerifier.withVirtualTime(
                                () -> {
                                    
                                    final var cache = Flux.fromIterable( 
                                                    DirectoryApiFixture.RESOLVED_GROUP_EMAILS
                                                            .keySet() 
                                            )
                                            .flatMap( email -> iut.getGroupsFor( email )
                                                    .map( DirectoryGroup::email )
                                                    .collectList()
                                            );

                                    final var fetch = Flux.fromIterable( 
                                                    DirectoryApiFixture.RESOLVED_GROUP_EMAILS
                                                            .keySet() 
                                            )
                                            .concatMap( email -> iut.getGroupsFor( email )
                                                    .map( DirectoryGroup::email )
                                                    .collectList()
                                            );

                                    return cache.thenMany( fetch );

                                },
                                () -> scheduler,
                                Long.MAX_VALUE
                        )
                        .expectSubscription()
                        .expectNoEvent( DELAY ) // Put in cache
                        .expectNoEvent( DELAY ); // Second one to try the non-existent groups

                for ( final var expected : DirectoryApiFixture.RESOLVED_GROUP_EMAILS.values() ) {

                    // Should have no delay since everything is cached
                    verifier.assertNext( groups -> assertThat( groups ) 
                            .containsExactlyInAnyOrderElementsOf( expected )
                    );

                }

                verifier.thenAwait( Duration.ofHours( 1 ) ).verifyComplete();

            }

            /* Tests that are affected by prefetching */
            /* The @Test needs to be added on the implementation */

            /**
             * Tests querying when data is stale but not expired.
             */
            public abstract void testStaleQuery();

        }

    }

    /**
     * Tests for a cached resolver with prefetching disabled.
     */
    @Nested
    public class WithCacheWithoutPrefetch extends WithCache {

        @Override
        protected boolean prefetch() {

            return false;

        }

        /**
         * Timing-dependent tests.
         */
        @Nested
        public class Timing extends WithCache.Timing {

            @Test
            @Override
            public void testStaleQuery() {

                final var email = "foo@org.com";
                final var expected = DirectoryApiFixture.RESOLVED_GROUP_EMAILS.get( email );
                final int depth = DirectoryApiFixture.RESOLVED_GROUP_DEPTH.get( email );
                // Direct + each level + non-existing
                final var fetchDelay = DELAY.multipliedBy( 1 + depth );

                StepVerifier.withVirtualTime(
                                () -> {
                                    
                                    final var fetch = Flux.defer( () -> iut.getGroupsFor( email ) )
                                            .map( DirectoryGroup::email )
                                            .collectList();

                                    return Flux.concat(
                                            fetch,
                                            fetch.delaySubscription( TTL_LIVE )
                                    );

                                },
                                () -> scheduler,
                                Long.MAX_VALUE
                        )
                        .expectSubscription()
                        .expectNoEvent( fetchDelay )
                        .assertNext( groups -> assertThat( groups ) 
                                .containsExactlyInAnyOrderElementsOf( expected )
                        )
                        .expectNoEvent( TTL_LIVE )
                        .expectNoEvent( fetchDelay )
                        .assertNext( groups -> assertThat( groups ) 
                                .containsExactlyInAnyOrderElementsOf( expected )
                        )
                        .verifyComplete();

            }

        }

    }

    /**
     * Tests for a cached resolver with prefetching enabled.
     */
    @Nested
    public class WithCacheWithPrefetch extends WithCache {

        @Override
        protected boolean prefetch() {

            return true;

        }

        /**
         * Timing-dependent tests.
         */
        @Nested
        public class Timing extends WithCache.Timing {

            @Test
            public void testStaleQuery() {

                final var email = "foo@org.com";
                final var expected = DirectoryApiFixture.RESOLVED_GROUP_EMAILS.get( email );
                final int depth = DirectoryApiFixture.RESOLVED_GROUP_DEPTH.get( email );
                // Direct + each level + non-existing
                final var fetchDelay = DELAY.multipliedBy( 1 + depth );

                StepVerifier.withVirtualTime(
                                () -> {
                                    
                                    final var fetch = Flux.defer( () -> iut.getGroupsFor( email ) )
                                            .map( DirectoryGroup::email )
                                            .collectList();

                                    return Flux.concat(
                                            fetch,
                                            fetch.delaySubscription( TTL_LIVE )
                                    );

                                },
                                () -> scheduler,
                                Long.MAX_VALUE
                        )
                        .expectSubscription()
                        .expectNoEvent( fetchDelay )
                        .assertNext( groups -> assertThat( groups ) 
                                .containsExactlyInAnyOrderElementsOf( expected )
                        )
                        .expectNoEvent( TTL_LIVE )
                        .expectNoEvent( DELAY ) // All cache refreshed at once with prefetch
                        .assertNext( groups -> assertThat( groups ) 
                                .containsExactlyInAnyOrderElementsOf( expected )
                        )
                        .verifyComplete();

            }

        }

    }
    
}
