package dev.sympho.google_group_resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.commons.collections4.ListUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.sympho.google_group_resolver.google.DirectoryGroup;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link LRUGroupCache}.
 */
public class LRUGroupCacheTest extends GroupCacheTest<LRUGroupCache> {

    /** The time an entry stays in valid status. */
    private static final Duration TTL_VALID = Duration.ofMinutes( 1 );

    /** The time an entry stays in stale status. */
    private static final Duration TTL_STALE = Duration.ofMinutes( 5 );

    /** The period between cleaner runs. */
    private static final Duration CLEANER_PERIOD = Duration.ofHours( 1 );

    /** The cache capacity. */
    private static final int CAPACITY = 10;

    @Override
    protected LRUGroupCache makeIUT() {

        return new LRUGroupCache( 
                directory, 
                TTL_VALID, 
                TTL_STALE, 
                CLEANER_PERIOD, 
                CAPACITY, 
                clock 
        );

    }

    /**
     * Starts the cleaner job.
     */
    @BeforeEach
    public void startCleaner() {

        iut.startCleaner();

    }

    /**
     * Stops the cleaner job.
     */
    @AfterEach
    public void stopCleaner() {

        iut.stopCleaner();

    }

    /**
     * Tests fetching the same entry multiple times.
     */
    @Test
    public void testFetchOneMultiple() {

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

            final var repeat = Mono.defer( () -> {
                
                final var cachedEntry = iut.get( email );

                assertThat( cachedEntry.valid() ).isTrue();
                assertThat( cachedEntry.value() ).isNotNull()
                        .containsExactlyInAnyOrderElementsOf( groups );

                return cachedEntry.latest();

            } ).repeat( 2 );

            return entry.latest().concatWith( repeat )
                    .doOnComplete( () -> assertThat( iut.size() ).isEqualTo( 1 ) );

        }, () -> scheduler, Long.MAX_VALUE )
                .expectSubscription()
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .verifyComplete();

    }

    /**
     * Tests fetching many entries multiple times.
     */
    @Test
    public void testFetchManyMultiple() {

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

            final var repeat = Flux.fromIterable( cases ).concatMap( e -> {
                
                final var cachedEntry = iut.get( e.getKey() );

                assertThat( cachedEntry.valid() ).isTrue();
                assertThat( cachedEntry.value() ).isNotNull()
                        .containsExactlyInAnyOrderElementsOf( e.getValue() );

                return cachedEntry.latest();

            } ).repeat( 2 );

            return Flux.fromIterable( entries ).flatMap( e -> e ).concatWith( repeat )
                    .doOnComplete( () -> assertThat( iut.size() ).isEqualTo( cases.size() ) );

        }, () -> scheduler, Long.MAX_VALUE )
                .expectSubscription()
                .expectNoEvent( delay );

        for ( int i = 0; i < 4; i++ ) {
            for ( final var entry : cases ) {

                verifier.assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( entry.getValue() ) 
                );

            }
        }
        
        verifier.verifyComplete();

    }

    /**
     * Tests an old entry updating through the chain to the latest value.
     */
    @Test
    @SuppressWarnings( "unchecked" )
    public void testOldEntry() {

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

        final var delay = Duration.ofSeconds( 1 );
        final var spacing = delay.plus( TTL_VALID );

        StepVerifier.withVirtualTime( () -> {

            final var groupsFlux = Flux.fromIterable( groups ).delaySubscription( delay );
            final var newGroupsFlux = Flux.fromIterable( newGroups ).delaySubscription( delay );
            Mockito.when( directory.getGroups( email ) )
                    .thenReturn( groupsFlux, groupsFlux, groupsFlux, newGroupsFlux );

            final var entry = iut.get( email );

            assertThat( entry.valid() ).isFalse();
            assertThat( entry.value() ).isNull();

            final var refresh = Mono.delay( TTL_VALID )
                    .then( Mono.fromSupplier( () -> iut.get( email ) ) )
                    .flatMap( e -> e.latest() );

            return entry.latest()
                    .concatWith( refresh.repeat( 2 ) )
                    .concatWith( entry.latest()
                            .doOnSubscribe( s -> {
                                assertThat( entry.valid() ).isFalse();
                                assertThat( entry.value() ).isNull();
                            } )
                            .delaySubscription( TTL_VALID ) 
                    )
                    .doOnComplete( () -> assertThat( iut.size() ).isEqualTo( 1 ) );

        }, () -> scheduler, Long.MAX_VALUE )
                .expectSubscription()
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .expectNoEvent( spacing )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .expectNoEvent( spacing )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .expectNoEvent( spacing )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( newGroups ) 
                )
                .expectNoEvent( spacing )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( newGroups ) 
                )
                .verifyComplete();

    }

    /**
     * Tests one expired entry being cleaned up in an otherwise empty cache.
     */
    @Test
    public void testExpiredCleanupOneRemainNone() {

        final var email = "test@foo.bar";
        final var groups = List.of( 
                new DirectoryGroup( "A", "a@foo.bar" ), 
                new DirectoryGroup( "B", "b@foo.bar" ), 
                new DirectoryGroup( "C", "c@foo.bar" )
        );

        final var delay = Duration.ofSeconds( 1 );

        final var buffer = Duration.ofMillis( 10 );
        final var untilBeforeClean = CLEANER_PERIOD
                .minus( delay )
                .minus( TTL_VALID )
                .minus( TTL_STALE )
                .minus( buffer );
        final var untilAfterClean = buffer.multipliedBy( 2 );

        final var totalWait = CLEANER_PERIOD.plus( buffer );

        Mockito.when( directory.getGroups( email ) )
                .thenAnswer( inv -> Flux.fromIterable( groups ).delaySubscription( delay ) );

        StepVerifier.withVirtualTime( () -> {

            final var entry = iut.get( email );

            assertThat( entry.valid() ).isFalse();
            assertThat( entry.value() ).isNull();

            final var value = entry.latest()
                    .doOnNext( result -> assertThat( result )
                            .containsExactlyInAnyOrderElementsOf( groups )
                    ).then();

            final var checkValid = Mono.fromRunnable( () -> {
                
                assertThat( iut.size() ).isEqualTo( 1 );
                final var entryValid = iut.get( email );
                assertThat( entryValid.valid() ).isTrue();
                assertThat( entryValid.value() ).isNotNull()
                        .containsExactlyInAnyOrderElementsOf( groups );

            } );

            final var checkStale = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( 1 );
                final var entryStale = iut.get( email );
                assertThat( entryStale.valid() ).isFalse();
                assertThat( entryStale.value() ).isNotNull()
                        .containsExactlyInAnyOrderElementsOf( groups );

            } ).delaySubscription( TTL_VALID );

            final var checkExpired = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( 1 );
                final var entryExpired = iut.get( email );
                assertThat( entryExpired.valid() ).isFalse();
                assertThat( entryExpired.value() ).isNull();

            } ).delaySubscription( TTL_STALE );

            final var checkBeforeClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( 1 );

            } ).delaySubscription( untilBeforeClean );

            final var checkAfterClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( 0 );

            } ).delaySubscription( untilAfterClean );

            return Flux.concat(
                    value,
                    checkValid,
                    checkStale,
                    checkExpired,
                    checkBeforeClean,
                    checkAfterClean
            );

        }, () -> scheduler, Long.MAX_VALUE )
                .thenAwait( totalWait )
                .verifyComplete();

    }

    /**
     * Tests one expired entry being cleaned up with other entries remaining.
     */
    @Test
    public void testExpiredCleanupOneRemainMany() {

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

        final var delay = Duration.ofSeconds( 1 );

        final var buffer = Duration.ofMillis( 10 );
        final var untilCacheExtras = CLEANER_PERIOD
                .minus( delay )
                .minus( TTL_VALID )
                .minus( TTL_STALE )
                .minus( delay )
                .minus( buffer );
        final var untilAfterClean = buffer.multipliedBy( 2 );

        final var totalWait = CLEANER_PERIOD.plus( buffer );

        Mockito.when( directory.getGroups( email ) )
                .thenAnswer( inv -> Flux.fromIterable( groups ).delaySubscription( delay ) );

        for ( final var entry : extras ) {

            final var extraEmail = entry.getKey();
            final var extraGroups = entry.getValue();

            Mockito.when( directory.getGroups( extraEmail ) )
                    .thenReturn( Flux.fromIterable( extraGroups ).delaySubscription( delay ) );

        }

        StepVerifier.withVirtualTime( () -> {

            final var entry = iut.get( email );

            assertThat( entry.valid() ).isFalse();
            assertThat( entry.value() ).isNull();

            final var value = entry.latest()
                    .doOnNext( result -> assertThat( result )
                            .containsExactlyInAnyOrderElementsOf( groups )
                    ).then();

            final var checkValid = Mono.fromRunnable( () -> {
                
                assertThat( iut.size() ).isEqualTo( 1 );
                final var entryValid = iut.get( email );
                assertThat( entryValid.valid() ).isTrue();
                assertThat( entryValid.value() ).isNotNull()
                        .containsExactlyInAnyOrderElementsOf( groups );

            } );

            final var checkStale = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( 1 );
                final var entryStale = iut.get( email );
                assertThat( entryStale.valid() ).isFalse();
                assertThat( entryStale.value() ).isNotNull()
                        .containsExactlyInAnyOrderElementsOf( groups );

            } ).delaySubscription( TTL_VALID );

            final var checkExpired = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( 1 );
                final var entryExpired = iut.get( email );
                assertThat( entryExpired.valid() ).isFalse();
                assertThat( entryExpired.value() ).isNull();

            } ).delaySubscription( TTL_STALE );

            final var cacheExtras = Mono.defer( () -> {

                assertThat( iut.size() ).isEqualTo( 1 );

                final var extraMonos = extras.stream()
                        .map( Map.Entry::getKey )
                        .map( iut::get )
                        .map( GroupCache.Entry::latest )
                        .toList();

                return Mono.zip( extraMonos, r -> Mono.empty() ).then();

            } ).delaySubscription( untilCacheExtras );

            final var checkBeforeClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( 1 + extras.size() );

            } );

            final var checkAfterClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( extras.size() );

            } ).delaySubscription( untilAfterClean );

            return Flux.concat(
                    value,
                    checkValid,
                    checkStale,
                    checkExpired,
                    cacheExtras,
                    checkBeforeClean,
                    checkAfterClean
            );

        }, () -> scheduler, Long.MAX_VALUE )
                .thenAwait( totalWait )
                .verifyComplete();

    }

    /**
     * Tests entries above the limit being trimmed during cleanup.
     */
    @Test
    public void testExcessTrim() {

        final var excess = 3;
        final var cases = IntStream.range( 0, CAPACITY + excess )
                .mapToObj( i -> Map.entry( "test-" + i + "@foo.bar", List.of(
                        new DirectoryGroup( "A" + i, "a" + i + "@foo.bar" ), 
                        new DirectoryGroup( "B" + i, "b" + i + "@foo.bar" ), 
                        new DirectoryGroup( "C" + i, "c" + i + "@foo.bar" )
                ) ) )
                .toList();

        final var delay = Duration.ofSeconds( 1 );

        final var buffer = Duration.ofMillis( 10 );
        final var initialDelay = CLEANER_PERIOD.minus( buffer ).minus( delay ).minus( delay );

        final var totalWait = CLEANER_PERIOD.plus( buffer );

        for ( final var entry : cases ) {

            final var extraEmail = entry.getKey();
            final var extraGroups = entry.getValue();

            Mockito.when( directory.getGroups( extraEmail ) )
                    .thenReturn( Flux.fromIterable( extraGroups ).delaySubscription( delay ) );

        }

        final var toCut = cases.subList( 0, excess );
        final var toStay = cases.subList( excess, cases.size() );

        StepVerifier.withVirtualTime( () -> {

            final var initial = Mono.delay( initialDelay ).then();

            final var cutMonos = toCut.stream()
                    .map( e -> Mono.defer( () -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest()
                                .doOnNext( groups -> assertThat( groups )
                                        .isNotNull()
                                        .containsExactlyInAnyOrderElementsOf( groups ) 
                                );

                    } ) )
                    .toList();

            final var cut = Mono.zip( cutMonos, r -> Mono.empty() ).then();

            final var stayMonos = toStay.stream()
                    .map( e -> Mono.defer( () -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest()
                                .doOnNext( groups -> assertThat( groups )
                                        .isNotNull()
                                        .containsExactlyInAnyOrderElementsOf( groups ) 
                                );

                    } ) )
                    .toList();

            final var stay = Mono.zip( stayMonos, r -> Mono.empty() ).then();

            final var checkBeforeClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( cases.size() );

            } );

            final var checkAfterClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( toStay.size() );

                toStay.forEach( e -> {

                    final var entry = iut.get( e.getKey() );

                    assertThat( entry.valid() ).isTrue();
                    assertThat( entry.value() ).isNotNull()
                            .containsExactlyInAnyOrderElementsOf( e.getValue() ); 

                } );

                assertThat( iut.size() ).isEqualTo( toStay.size() );

                toCut.forEach( e -> {

                    final var entry = iut.get( e.getKey() );

                    assertThat( entry.valid() ).isFalse();
                    assertThat( entry.value() ).isNull(); 

                } );

                assertThat( iut.size() ).isEqualTo( cases.size() );

            } ).delaySubscription( buffer.multipliedBy( 2 ) );

            return Flux.concat(
                    initial,
                    cut,
                    stay,
                    checkBeforeClean,
                    checkAfterClean
            );

        }, () -> scheduler, Long.MAX_VALUE )
                .thenAwait( totalWait )
                .verifyComplete();

    }

    /**
     * Tests entries above the limit being trimmed during cleanup, where some entries are accessed
     * again after being retrieved.
     */
    @Test
    public void testExcessTrimLRU() {

        final var excess = 3;
        final var cases = IntStream.range( 0, CAPACITY + excess )
                .mapToObj( i -> Map.entry( "test-" + i + "@foo.bar", List.of(
                        new DirectoryGroup( "A" + i, "a" + i + "@foo.bar" ), 
                        new DirectoryGroup( "B" + i, "b" + i + "@foo.bar" ), 
                        new DirectoryGroup( "C" + i, "c" + i + "@foo.bar" )
                ) ) )
                .toList();

        final var delay = Duration.ofSeconds( 1 );

        final var buffer = Duration.ofMillis( 10 );
        final var initialDelay = CLEANER_PERIOD.minus( buffer )
                .minus( buffer )
                .minus( delay )
                .minus( delay );

        final var totalWait = CLEANER_PERIOD.plus( buffer );

        for ( final var entry : cases ) {

            final var extraEmail = entry.getKey();
            final var extraGroups = entry.getValue();

            Mockito.when( directory.getGroups( extraEmail ) )
                    .thenReturn( Flux.fromIterable( extraGroups ).delaySubscription( delay ) );

        }

        final var toCut = cases.subList( 0, excess );
        final var toStay = cases.subList( excess, cases.size() );

        StepVerifier.withVirtualTime( () -> {

            final var initial = Mono.delay( initialDelay ).then();

            final var stayMonos = toStay.stream()
                    .map( e -> Mono.defer( () -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest()
                                .doOnNext( groups -> assertThat( groups )
                                        .isNotNull()
                                        .containsExactlyInAnyOrderElementsOf( groups ) 
                                );

                    } ) )
                    .toList();

            final var stay = Mono.zip( stayMonos, r -> Mono.empty() ).then();

            final var cutMonos = toCut.stream()
                    .map( e -> Mono.defer( () -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest()
                                .doOnNext( groups -> assertThat( groups )
                                        .isNotNull()
                                        .containsExactlyInAnyOrderElementsOf( groups ) 
                                );

                    } ) )
                    .toList();

            final var cut = Mono.zip( cutMonos, r -> Mono.empty() ).then();

            final var checkBeforeClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( cases.size() );

                toStay.forEach( e -> {

                    final var entry = iut.get( e.getKey() );

                    assertThat( entry.valid() ).isTrue();
                    assertThat( entry.value() ).isNotNull()
                            .containsExactlyInAnyOrderElementsOf( e.getValue() );

                } );

                assertThat( iut.size() ).isEqualTo( cases.size() );

            } ).delaySubscription( buffer );

            final var checkAfterClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( toStay.size() );

                toStay.forEach( e -> {

                    final var entry = iut.get( e.getKey() );

                    assertThat( entry.valid() ).isTrue();
                    assertThat( entry.value() ).isNotNull()
                            .containsExactlyInAnyOrderElementsOf( e.getValue() ); 

                } );

                assertThat( iut.size() ).isEqualTo( toStay.size() );

                toCut.forEach( e -> {

                    final var entry = iut.get( e.getKey() );

                    assertThat( entry.valid() ).isFalse();
                    assertThat( entry.value() ).isNull(); 

                } );

                assertThat( iut.size() ).isEqualTo( cases.size() );

            } ).delaySubscription( buffer.multipliedBy( 2 ) );

            return Flux.concat(
                    initial,
                    stay,
                    cut,
                    checkBeforeClean,
                    checkAfterClean
            );

        }, () -> scheduler, Long.MAX_VALUE )
                .thenAwait( totalWait )
                .verifyComplete();

    }

    /**
     * Tests expired entries <i>and</i> entries above the limit being trimmed during cleanup.
     */
    @Test
    public void testExpiredAndExcessTrim() {

        final var expired = 3;
        final var excess = 3;
        final var cases = IntStream.range( 0, CAPACITY + expired + excess )
                .mapToObj( i -> Map.entry( "test-" + i + "@foo.bar", List.of(
                        new DirectoryGroup( "A" + i, "a" + i + "@foo.bar" ), 
                        new DirectoryGroup( "B" + i, "b" + i + "@foo.bar" ), 
                        new DirectoryGroup( "C" + i, "c" + i + "@foo.bar" )
                ) ) )
                .toList();

        final var delay = Duration.ofSeconds( 1 );

        final var buffer = Duration.ofMillis( 10 );
        final var expireDelay = CLEANER_PERIOD.minus( buffer )
                .minus( delay.multipliedBy( 3 ) );

        final var totalWait = CLEANER_PERIOD.plus( buffer );

        for ( final var entry : cases ) {

            final var extraEmail = entry.getKey();
            final var extraGroups = entry.getValue();

            Mockito.when( directory.getGroups( extraEmail ) )
                    .thenReturn( Flux.fromIterable( extraGroups ).delaySubscription( delay ) );

        }

        final var toExpire = cases.subList( 0, expired );
        final var toCut = cases.subList( expired, expired + excess );
        final var toStay = cases.subList( expired + excess, cases.size() );

        StepVerifier.withVirtualTime( () -> {

            final var expireMonos = toExpire.stream()
                    .map( e -> Mono.defer( () -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest()
                                .doOnNext( groups -> assertThat( groups )
                                        .isNotNull()
                                        .containsExactlyInAnyOrderElementsOf( groups ) 
                                );

                    } ) )
                    .toList();

            final var expire = Mono.zip( expireMonos, r -> Mono.empty() ).then();

            final var cutMonos = toCut.stream()
                    .map( e -> Mono.defer( () -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest()
                                .doOnNext( groups -> assertThat( groups )
                                        .isNotNull()
                                        .containsExactlyInAnyOrderElementsOf( groups ) 
                                );

                    } ) )
                    .toList();

            final var cut = Mono.zip( cutMonos, r -> Mono.empty() ).then()
                    .delaySubscription( expireDelay );

            final var stayMonos = toStay.stream()
                    .map( e -> Mono.defer( () -> {

                        final var entry = iut.get( e.getKey() );

                        assertThat( entry.valid() ).isFalse();
                        assertThat( entry.value() ).isNull();

                        return entry.latest()
                                .doOnNext( groups -> assertThat( groups )
                                        .isNotNull()
                                        .containsExactlyInAnyOrderElementsOf( groups ) 
                                );

                    } ) )
                    .toList();

            final var stay = Mono.zip( stayMonos, r -> Mono.empty() ).then();

            final var checkBeforeClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( cases.size() );

            } );

            final var checkAfterClean = Mono.fromRunnable( () -> {

                assertThat( iut.size() ).isEqualTo( toStay.size() );

                toStay.forEach( e -> {

                    final var entry = iut.get( e.getKey() );

                    assertThat( entry.valid() ).isTrue();
                    assertThat( entry.value() ).isNotNull()
                            .containsExactlyInAnyOrderElementsOf( e.getValue() ); 

                } );

                assertThat( iut.size() ).isEqualTo( toStay.size() );

                ListUtils.union( toExpire, toCut ).forEach( e -> {

                    final var entry = iut.get( e.getKey() );

                    assertThat( entry.valid() ).isFalse();
                    assertThat( entry.value() ).isNull(); 

                } );

                assertThat( iut.size() ).isEqualTo( cases.size() );

            } ).delaySubscription( buffer.multipliedBy( 2 ) );

            return Flux.concat(
                    expire,
                    cut,
                    stay,
                    checkBeforeClean,
                    checkAfterClean
            );

        }, () -> scheduler, Long.MAX_VALUE )
                .thenAwait( totalWait )
                .verifyComplete();

    }

    /**
     * Test that a valid cached value being used immediately when 
     * {@link GroupCache.Entry#latest() latest()} is requested.
     */
    @Test
    public void testCachedEntry() {

        final var email = "test@foo.bar";
        final var groups = List.of( 
                new DirectoryGroup( "A", "a@foo.bar" ), 
                new DirectoryGroup( "B", "b@foo.bar" ), 
                new DirectoryGroup( "C", "c@foo.bar" )
        );

        final var delay = Duration.ofSeconds( 1 );

        Mockito.when( directory.getGroups( email ) )
                .thenReturn( Flux.fromIterable( groups ).delaySubscription( delay ) );

        StepVerifier.withVirtualTime( () -> {

            final var entry = iut.get( email );

            assertThat( entry.valid() ).isFalse();
            assertThat( entry.value() ).isNull();

            return entry.latest()
                    .doOnSuccess( s -> assertThat( iut.size() ).isEqualTo( 1 ) )
                    .concatWith( Mono.defer( () -> entry.latest() ) )
                    .concatWith( Mono.defer( () -> {

                        final var e = iut.get( email );

                        assertThat( e.valid() ).isTrue();
                        assertThat( e.value() ).isNotNull()
                                .containsExactlyInAnyOrderElementsOf( groups );

                        return e.latest();
                        
                    } ) );

        }, () -> scheduler, Long.MAX_VALUE )
                .expectSubscription()
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .verifyComplete();

    }

    @Test
    @SuppressWarnings( "unchecked" )
    public void testRefreshConflict() {

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

        final var delay = Duration.ofSeconds( 1 );

        StepVerifier.withVirtualTime( () -> {

            final var groupsFlux = Flux.fromIterable( groups ).delaySubscription( delay );
            final var newGroupsFlux = Flux.fromIterable( newGroups ).delaySubscription( delay );
            Mockito.when( directory.getGroups( email ) )
                    .thenReturn( groupsFlux, groupsFlux, newGroupsFlux, newGroupsFlux );

            final var entry = iut.get( email );

            assertThat( entry.valid() ).isFalse();
            assertThat( entry.value() ).isNull();

            return entry.latest()
                    .doOnSuccess( s -> assertThat( iut.size() ).isEqualTo( 1 ) )
                    .concatWith( 
                            Mono.defer( () -> {

                                assertThat( iut.size() ).isEqualTo( 0 );
                                return iut.get( email ).latest();

                            } )
                            .doOnSuccess( s -> assertThat( iut.size() ).isEqualTo( 1 ) )
                            .delaySubscription( CLEANER_PERIOD )
                    )
                    .concatWith( 
                            Mono.defer( () -> {

                                assertThat( iut.size() ).isEqualTo( 1 );
                                return iut.get( email ).latest();

                            } )
                            .doOnSuccess( s -> assertThat( iut.size() ).isEqualTo( 1 ) )
                            .delaySubscription( TTL_STALE )
                    )
                    .concatWith( 
                            Mono.defer( () -> {

                                assertThat( iut.size() ).isEqualTo( 1 );
                                return entry.latest();

                            } ) 
                            .doOnSuccess( s -> assertThat( iut.size() ).isEqualTo( 1 ) )
                            .delaySubscription( TTL_STALE )
                    );

        }, () -> scheduler, Long.MAX_VALUE )
                .expectSubscription()
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .expectNoEvent( CLEANER_PERIOD )
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( groups ) 
                )
                .expectNoEvent( TTL_STALE )
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( newGroups ) 
                )
                .expectNoEvent( TTL_STALE )
                .expectNoEvent( delay ) // There is a fetch before detecting the updated entry
                .expectNoEvent( delay )
                .assertNext( result -> assertThat( result )
                        .containsExactlyInAnyOrderElementsOf( newGroups ) 
                )
                .verifyComplete();

    }
    
}
