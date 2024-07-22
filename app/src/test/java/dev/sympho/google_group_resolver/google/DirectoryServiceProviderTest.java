package dev.sympho.google_group_resolver.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.sympho.google_group_resolver.CustomResourceLocks;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.function.Tuple2;

/**
 * Tests for {@link DirectoryServiceProvider}.
 */
@ExtendWith( MockitoExtension.class )
@Timeout( 5 )
@ResourceLock( CustomResourceLocks.SCHEDULERS )
public class DirectoryServiceProviderTest {

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( 
            DirectoryServiceProviderTest.class 
    );

    /** Batch size to use. */
    private static final int BATCH_SIZE = 100;

    /** Batch timeout to use. */
    private static final Duration BATCH_TIMEOUT = Duration.ofMillis( 100 );

    /** The virtual scheduler to use. */
    VirtualTimeScheduler scheduler;

    /** API client mock. */
    DirectoryApiMock apiClient;

    /** Instance under test. */
    DirectoryServiceProvider iut;

    /**
     * Starts the task handler before each test.
     */
    @BeforeEach
    public void startUp() {

        scheduler = VirtualTimeScheduler.getOrSet( true );

        apiClient = DirectoryApiFixture.apiClient();

        iut = new DirectoryServiceProvider( apiClient, BATCH_SIZE, BATCH_TIMEOUT );
        iut.start();

    }

    /**
     * Stops the task handler after each test.
     */
    @AfterEach
    public void shutDown() {

        iut.stop();

        VirtualTimeScheduler.reset();

    }

    /**
     * Tests with only success results.
     */
    @Nested
    class Success {

        /**
         * Argument provider for {@link #testSingleOneNoPages(String)}.
         *
         * @return The arguments.
         */
        private static Stream<String> testSingleOneNoPages() {

            return DirectoryApiFixture.API_GROUP_LIST.stream()
                    .filter( e -> e.getValue().size() <= DirectoryApiMock.PAGE_SIZE )
                    .map( Map.Entry::getKey );

        }

        /**
         * Tests doing a single query to the provider, where the
         * query only results in one page.
         *
         * @param email The email to query for.
         */
        @ParameterizedTest
        @MethodSource
        public void testSingleOneNoPages( final String email ) throws IOException {

            final var groups = DirectoryApiFixture.API_GROUP_MAP.get( email );

            final var expected = groups.stream()
                    .map( g -> new DirectoryGroup( g.name(), g.email() ) )
                    .toList();

            StepVerifier.withVirtualTime( 
                            () -> iut.getGroups( email ).collectList(), 
                            () -> scheduler,
                            Long.MAX_VALUE 
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .assertNext( actual -> assertThat( actual )
                            .containsExactlyInAnyOrderElementsOf( expected ) 
                    )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );

        }

        /**
         * Argument provider for {@link #testSingleOneWithPages(String)}.
         *
         * @return The arguments.
         */
        private static Stream<String> testSingleOneWithPages() {

            return DirectoryApiFixture.API_GROUP_LIST.stream()
                    .filter( e -> e.getValue().size() > DirectoryApiMock.PAGE_SIZE )
                    .map( Map.Entry::getKey );

        }

        /**
         * Tests doing a single query to the provider, where the
         * query results in multiple pages.
         *
         * @param email The email to query for.
         */
        @ParameterizedTest
        @MethodSource
        public void testSingleOneWithPages( final String email ) throws IOException {

            final var groups = DirectoryApiFixture.API_GROUP_MAP.get( email );
            final var pages = Math.ceilDiv( groups.size(), DirectoryApiMock.PAGE_SIZE );

            final var expected = groups.stream()
                    .map( g -> new DirectoryGroup( g.name(), g.email() ) )
                    .toList();
            
            StepVerifier.withVirtualTime( 
                            () -> iut.getGroups( email ).collectList(), 
                            () -> scheduler,
                            Long.MAX_VALUE 
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT.multipliedBy( pages ) )
                    .assertNext( actual -> assertThat( actual )
                            .containsExactlyInAnyOrderElementsOf( expected ) 
                    )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( pages );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );

        }

        /**
         * Tests doing multiple queries to the provider with sequential single queries.
         */
        @Test
        public void testAllSequential() throws IOException {

            final var verifier = StepVerifier.withVirtualTime( () -> {

                return Flux.fromIterable( DirectoryApiFixture.API_GROUP_LIST )
                        .map( Map.Entry::getKey )
                        .concatMap( email -> iut.getGroups( email ).collectList() );

            }, () -> scheduler, Long.MAX_VALUE ).expectSubscription();

            for ( final var entry : DirectoryApiFixture.API_GROUP_LIST ) {

                final var expected = entry.getValue().stream()
                        .map( g -> new DirectoryGroup( g.name(), g.email() ) )
                        .toList();

                final var pages = Math.ceilDiv( expected.size(), DirectoryApiMock.PAGE_SIZE );

                verifier.expectNoEvent( BATCH_TIMEOUT.multipliedBy( pages ) )
                        .assertNext( actual -> assertThat( actual )
                                .containsExactlyInAnyOrderElementsOf( expected ) 
                        );

            }

            verifier.thenAwait( Duration.ofDays( 1 ) ).verifyComplete();

            final var totalPages = DirectoryApiFixture.API_GROUP_LIST.stream()
                    .map( Map.Entry::getValue )
                    .mapToInt( List::size )
                    .map( c -> Math.ceilDiv( c, DirectoryApiMock.PAGE_SIZE ) )
                    .sum();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( totalPages );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );

        }

        /**
         * Tests doing multiple queries to the provider with a single batch query.
         */
        @Test
        public void testAllBatchedSingle() throws IOException {

            // Since everything starts on the same batch, it will take as many batches as needed
            // to get all the pages of the largest result
            final var batches = DirectoryApiFixture.API_GROUP_LIST.stream()
                    .map( Map.Entry::getValue )
                    .mapToInt( List::size )
                    .map( c -> Math.ceilDiv( c, DirectoryApiMock.PAGE_SIZE ) )
                    .max()
                    .getAsInt();

            StepVerifier.withVirtualTime( () -> {

                return Flux.fromIterable( DirectoryApiFixture.API_GROUP_MAP.keySet() )
                        .flatMap( email -> Mono.zip( 
                                Mono.just( email ), 
                                iut.getGroups( email ).collectList()
                        ) )
                        .collectMap( Tuple2::getT1, Tuple2::getT2 );

            } )
            .expectSubscription()
            .expectNoEvent( BATCH_TIMEOUT.multipliedBy( batches ) )
            .assertNext( result -> {

                assertThat( result.keySet() ).containsExactlyInAnyOrderElementsOf( 
                        DirectoryApiFixture.API_GROUP_MAP.keySet()
                );
                for ( final var entry : result.entrySet() ) {

                    final var actual = entry.getValue();
                    final var expected = DirectoryApiFixture.API_GROUP_MAP
                            .get( entry.getKey() )
                            .stream()
                            .map( g -> new DirectoryGroup( g.name(), g.email() ) )
                            .toList();
                            
                    assertThat( actual ).containsExactlyInAnyOrderElementsOf( expected );

                }

            } ).verifyComplete();

            // Can't assume specific number of batches since if one entry had more pages than
            // the rest it might use a few single queries
            assertThat( apiClient.batchCount() ).isGreaterThan( 0 );

        }

        /**
         * Tests doing multiple queries to the provider with multiple batch queries.
         */
        @Test
        public void testAllBatchedSplit() throws IOException {

            final var batchSize = 2;

            final var batches = IntStream.range( 0, DirectoryApiFixture.API_GROUP_LIST.size() )
                    .map( i -> {

                        final var batch = i / batchSize;
                        final var entry = DirectoryApiFixture.API_GROUP_LIST.get( i );
                        final var groups = entry.getValue().size();
                        final var pages = Math.ceilDiv( groups, DirectoryApiMock.PAGE_SIZE );
                        return pages + batch;

                    } )
                    .max()
                    .getAsInt();

            StepVerifier.withVirtualTime( () -> {

                return Flux.fromIterable( DirectoryApiFixture.API_GROUP_LIST )
                        .map( Map.Entry::getKey )
                        .buffer( batchSize )
                        .delayElements( BATCH_TIMEOUT )
                        .flatMapIterable( Function.identity() )
                        .flatMap( email -> Mono.zip( 
                                Mono.just( email ), 
                                iut.getGroups( email ).collectList()
                        ) )
                        .collectMap( Tuple2::getT1, Tuple2::getT2 )
                        .doOnNext( e -> LOG.info( "All done" ) );

            } )
            .expectSubscription()
            .expectNoEvent( BATCH_TIMEOUT.multipliedBy( batches ) )
            .consumeNextWith( result -> {

                assertThat( result.keySet() ).containsExactlyInAnyOrderElementsOf( 
                        DirectoryApiFixture.API_GROUP_MAP.keySet() 
                );
                for ( final var entry : result.entrySet() ) {

                    final var actual = entry.getValue();
                    final var expected = DirectoryApiFixture.API_GROUP_MAP
                            .get( entry.getKey() )
                            .stream()
                            .map( g -> new DirectoryGroup( g.name(), g.email() ) )
                            .toList();
                            
                    assertThat( actual ).containsExactlyInAnyOrderElementsOf( expected );

                }

            } ).verifyComplete();

            // Can't assume specific number of batches since if one entry had more pages than
            // the rest it might use a few single queries
            assertThat( apiClient.batchCount() ).isGreaterThan( 1 );

        }

    }

    /**
     * Tests with failure results.
     */
    @Nested
    class Failure {

        /**
         * Verifies that the error is the expected one.
         *
         * @param error The received error.
         * @return If the error is correct.
         */
        private boolean verifyError( final Throwable error ) {

            return error instanceof DirectoryService.FailedException e && e.code() == 404;

        }

        /**
         * Tests querying a non-existing email.
         */
        @Test
        public void testFailSingle() throws IOException {

            StepVerifier.withVirtualTime( 
                            () -> iut.getGroups( "non-existing@test.com" ).collectList(),
                            () -> scheduler,
                            Long.MAX_VALUE
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .verifyErrorMatches( this::verifyError );

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );
        
        }

        /**
         * Tests querying non-existing emails as a batch.
         */
        @Test
        public void testFailBatch() throws IOException {

            final var emails = List.of( 
                    "non-existing-1@test.com", 
                    "non-existing-2@test.com", 
                    "non-existing-3@test.com"
            );

            StepVerifier.withVirtualTime(
                            () -> Flux.fromIterable( emails )
                                    .flatMap( email -> iut.getGroups( email )
                                            .collectList()
                                            .materialize() 
                                    )
                                    .filter( s -> s.isOnError() )
                                    .map( s -> s.getThrowable() ),
                            () -> scheduler,
                            Long.MAX_VALUE
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .expectNextMatches( this::verifyError )
                    .expectNextMatches( this::verifyError )
                    .expectNextMatches( this::verifyError )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            assertThat( apiClient.batchCount() ).isEqualTo( 1 );
        
        }

    }

    /**
     * Tests that result in an error being thrown.
     */
    @Nested
    class Error {

        /**
         * Verifies that the error is the expected one.
         *
         * @param error The received error.
         * @param expectedMessage The expected inner error message.
         * @return If the error is correct.
         */
        private boolean verifyError( final Throwable error, final String expectedMessage ) {

            return DirectoryServiceProvider.ERROR_UNEXPECTED_EXCEPTION.equals( error.getMessage() ) 
                    && error.getCause() != null 
                    && expectedMessage.equals( error.getCause().getMessage() );

        }

        /**
         * Verifies that the error is the expected global one.
         *
         * @param error The received error.
         * @return If the error is correct.
         */
        private boolean verifyErrorGlobal( final Throwable error ) {

            return verifyError( error, DirectoryApiMock.ERROR_MESSAGE_GLOBAL );

        }

        /**
         * Verifies that the error is the expected query one.
         *
         * @param error The received error.
         * @return If the error is correct.
         */
        private boolean verifyErrorQuery( final Throwable error ) {

            return verifyError( error, DirectoryApiMock.ERROR_MESSAGE_QUERY );

        }

        /**
         * Tests a global error in a single request.
         */
        @Test
        public void testGlobalErrorSingle() throws IOException {

            apiClient.setThrowError( true );
            StepVerifier.withVirtualTime( 
                            () -> iut.getGroups( "foo@org.com" ).collectList(),
                            () -> scheduler,
                            Long.MAX_VALUE
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .verifyErrorMatches( this::verifyErrorGlobal );

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );
        
        }

        /**
         * Tests a global error in a batch request.
         */
        @Test
        public void testGlobalErrorBatch() throws IOException {

            apiClient.setThrowError( true );

            StepVerifier.withVirtualTime(
                            () -> Flux.range( 0, 3 )
                                    .map( c -> "test" + c + "@test.com" )
                                    .flatMap( email -> iut.getGroups( email )
                                            .collectList()
                                            .materialize()
                                    )
                                    .filter( s -> s.isOnError() )
                                    .map( s -> s.getThrowable() ),
                            () -> scheduler,
                            Long.MAX_VALUE
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .expectNextMatches( this::verifyErrorGlobal )
                    .expectNextMatches( this::verifyErrorGlobal )
                    .expectNextMatches( this::verifyErrorGlobal )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            assertThat( apiClient.batchCount() ).isEqualTo( 1 );
        
        }

        /**
         * Tests recovering from a temporary global error.
         */
        @Test
        public void testGlobalErrorRecovery() throws IOException {

            final var email = "group-A@org.com";
            final var groups = DirectoryApiFixture.API_GROUP_MAP.get( email );
            final var pages = Math.ceilDiv( groups.size(), DirectoryApiMock.PAGE_SIZE );

            final var expected = groups.stream()
                    .map( g -> new DirectoryGroup( g.name(), g.email() ) )
                    .toList();

            StepVerifier.withVirtualTime( () -> {

                final var error = Mono.defer( () -> iut.getGroups( email ).collectList() )
                        .materialize()
                        .doOnNext( s -> {

                            assertThat( s.isOnError() ).isTrue();
                            assertThat( verifyErrorGlobal( s.getThrowable() ) ).isTrue();

                        } ).then().doOnSubscribe( s -> apiClient.setThrowError( true ) );

                final var success = Mono.defer( () -> iut.getGroups( email ).collectList() )
                        .doOnNext( actual -> assertThat( actual )
                        .containsExactlyInAnyOrderElementsOf( expected )
                ).then().doOnSubscribe( s -> apiClient.setThrowError( false ) );

                return Flux.concat(
                    error,
                    success
                );

            }, () -> scheduler, Long.MAX_VALUE )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT.multipliedBy( 1 + pages ) )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( pages + 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );

        }

        /**
         * Argument provider for {@link #testQueryErrorSingle(String)}.
         *
         * @return The arguments.
         */
        private static Stream<String> testQueryErrorSingle() {

            return DirectoryApiFixture.ERROR_QUERIES.stream();

        }

        /**
         * Tests querying an email that results in an error, using a single request.
         *
         * @param email The email to query.
         */
        @ParameterizedTest
        @MethodSource
        public void testQueryErrorSingle( final String email ) throws IOException {

            StepVerifier.withVirtualTime( 
                            () -> iut.getGroups( email ).collectList(),
                            () -> scheduler,
                            Long.MAX_VALUE
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .verifyErrorMatches( this::verifyErrorQuery );

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );
        
        }

        /**
         * Tests querying an email that results in an error, using a batch request.
         */
        @Test
        public void testQueryErrorBatch() throws IOException {

            StepVerifier.withVirtualTime( 
                            () -> Flux.fromIterable( DirectoryApiFixture.ERROR_QUERIES )
                                    .flatMap( email -> iut.getGroups( email )
                                            .collectList()
                                            .materialize() 
                                    )
                                    .filter( s -> s.isOnError() )
                                    .map( s -> s.getThrowable() ),
                            () -> scheduler,
                            Long.MAX_VALUE
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .expectNextMatches( this::verifyErrorQuery )
                    .expectNextMatches( this::verifyErrorQuery )
                    .expectNextMatches( this::verifyErrorQuery )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            assertThat( apiClient.batchCount() ).isEqualTo( 1 ); 
        
        }

        /**
         * Tests mixing success and error queries in one batch.
         */
        @Test
        public void testQueryErrorBatchMixed() throws IOException {

            final var emails = Stream.concat(
                    DirectoryApiFixture.ERROR_QUERIES.stream(),
                    DirectoryApiFixture.API_GROUP_MAP.keySet().stream()
            );

            // Since everything starts on the same batch, it will take as many batches as needed
            // to get all the pages of the largest result
            final var batches = DirectoryApiFixture.API_GROUP_LIST.stream()
                    .map( Map.Entry::getValue )
                    .mapToInt( List::size )
                    .map( c -> Math.ceilDiv( c, DirectoryApiMock.PAGE_SIZE ) )
                    .max()
                    .getAsInt();

            StepVerifier.withVirtualTime(
                            () -> Flux.fromStream( emails )
                                    .flatMap( email -> iut.getGroups( email )
                                            .collectList()
                                            .materialize() 
                                    )
                                    .filter( s -> s.isOnError() )
                                    .map( s -> s.getThrowable() ),
                            () -> scheduler,
                            Long.MAX_VALUE
                    )
                    .expectSubscription()
                    .expectNoEvent( BATCH_TIMEOUT )
                    .expectNextMatches( this::verifyErrorQuery )
                    .expectNextMatches( this::verifyErrorQuery )
                    .expectNextMatches( this::verifyErrorQuery )
                    .expectNoEvent( BATCH_TIMEOUT.multipliedBy( batches - 1 ) )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            assertThat( apiClient.batchCount() ).isGreaterThan( 0 );
        
        }

    }
    
}
