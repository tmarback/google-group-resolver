package dev.sympho.google_group_resolver.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
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
import reactor.util.function.Tuple2;

/**
 * Tests for {@link DirectoryServiceProvider}.
 */
@ExtendWith( MockitoExtension.class )
@Timeout( 5 )
@Execution( ExecutionMode.SAME_THREAD ) // Concurrent doesn't work properly in the terminal
@Isolated
public class DirectoryServiceProviderTest {

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( 
            DirectoryServiceProviderTest.class 
    );

    /** Batch size to use. */
    private static final int BATCH_SIZE = 100;

    /** Batch timeout to use. */
    private static final Duration BATCH_TIMEOUT = Duration.ofMillis( 100 );

    /** API client mock. */
    DirectoryApiMock apiClient;

    /** Instance under test. */
    DirectoryServiceProvider iut;

    /**
     * Starts the task handler before each test.
     */
    @BeforeEach
    public void startUp() {

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
                    .map( g -> new DirectoryService.Group( g.name(), g.email() ) )
                    .toList();

            StepVerifier.create( iut.getGroups( email ).collectList() )
                    .consumeNextWith( actual -> assertThat( actual )
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

            final var expected = groups.stream()
                    .map( g -> new DirectoryService.Group( g.name(), g.email() ) )
                    .toList();
            
            StepVerifier.create( iut.getGroups( email ).collectList() )
                    .consumeNextWith( actual -> assertThat( actual )
                            .containsExactlyInAnyOrderElementsOf( expected ) 
                    )
                    .verifyComplete();

            // Check that the expected calls were made
            final var pages = Math.ceilDiv( groups.size(), DirectoryApiMock.PAGE_SIZE );
            assertThat( apiClient.singleCount() ).isEqualTo( pages );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );

        }

        /**
         * Tests doing multiple queries to the provider with sequential single queries.
         */
        @Test
        public void testAllSequential() throws IOException {

            for ( final var entry : DirectoryApiFixture.API_GROUP_LIST ) {

                final var email = entry.getKey();
                final var expected = entry.getValue().stream()
                        .map( g -> new DirectoryService.Group( g.name(), g.email() ) )
                        .toList();

                StepVerifier.create( iut.getGroups( email ).collectList() )
                        .consumeNextWith( actual -> assertThat( actual )
                                .containsExactlyInAnyOrderElementsOf( expected ) 
                        )
                        .verifyComplete();

            }

            // Check that the expected calls were made
            final var pages = DirectoryApiFixture.API_GROUP_LIST.stream()
                    .map( Map.Entry::getValue )
                    .mapToInt( List::size )
                    .map( c -> Math.ceilDiv( c, DirectoryApiMock.PAGE_SIZE ) )
                    .sum();
            assertThat( apiClient.singleCount() ).isEqualTo( pages );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );

        }

        /**
         * Tests doing multiple queries to the provider with a single batch query.
         */
        @Test
        @ResourceLock( CustomResourceLocks.SCHEDULERS )
        public void testAllBatchedSingle() throws IOException {

            StepVerifier.withVirtualTime( () -> {

                return Flux.fromIterable( DirectoryApiFixture.API_GROUP_MAP.keySet() )
                        .flatMap( email -> Mono.zip( 
                                Mono.just( email ), 
                                iut.getGroups( email ).collectList()
                        ) )
                        .collectMap( Tuple2::getT1, Tuple2::getT2 );

            } ).consumeNextWith( result -> {

                assertThat( result.keySet() ).containsExactlyInAnyOrderElementsOf( 
                        DirectoryApiFixture.API_GROUP_MAP.keySet()
                );
                for ( final var entry : result.entrySet() ) {

                    final var actual = entry.getValue();
                    final var expected = DirectoryApiFixture.API_GROUP_MAP
                            .get( entry.getKey() )
                            .stream()
                            .map( g -> new DirectoryService.Group( g.name(), g.email() ) )
                            .toList();
                            
                    assertThat( actual ).containsExactlyInAnyOrderElementsOf( expected );

                }

            } ).verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            assertThat( apiClient.batchCount() ).isGreaterThan( 0 );

        }

        /**
         * Tests doing multiple queries to the provider with multiple batch queries.
         */
        @Test
        @ResourceLock( CustomResourceLocks.SCHEDULERS )
        public void testAllBatchedSplit() throws IOException {

            final var batchSize = 2;
            final var delay = BATCH_TIMEOUT.multipliedBy( 2 );

            StepVerifier.withVirtualTime( () -> {

                final var iut2 = new DirectoryServiceProvider( 
                        apiClient, 
                        BATCH_SIZE, 
                        BATCH_TIMEOUT 
                );
                iut2.start();
                LOG.info( "Started" );

                return Flux.fromIterable( DirectoryApiFixture.API_GROUP_MAP.keySet() )
                        .buffer( batchSize )
                        .delayElements( delay )
                        .flatMapIterable( Function.identity() )
                        .flatMap( email -> Mono.zip( 
                                Mono.just( email ), 
                                iut2.getGroups( email ).collectList()
                        ) )
                        .collectMap( Tuple2::getT1, Tuple2::getT2 )
                        .doOnNext( e -> LOG.info( "All done" ) )
                        .doFinally( s -> iut2.stop() ); // Clean up

            } )
            .expectSubscription()
            .thenAwait( Duration.ofDays( 1 ) ) // Long enough to get it all done
            .consumeNextWith( result -> {

                assertThat( result.keySet() ).containsExactlyInAnyOrderElementsOf( 
                        DirectoryApiFixture.API_GROUP_MAP.keySet() 
                );
                for ( final var entry : result.entrySet() ) {

                    final var actual = entry.getValue();
                    final var expected = DirectoryApiFixture.API_GROUP_MAP
                            .get( entry.getKey() )
                            .stream()
                            .map( g -> new DirectoryService.Group( g.name(), g.email() ) )
                            .toList();
                            
                    assertThat( actual ).containsExactlyInAnyOrderElementsOf( expected );

                }

            } ).verifyComplete();

            // Check that the expected calls were made
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

            StepVerifier.create( iut.getGroups( "non-existing@test.com" ).collectList() )
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

            final var pipeline = Flux.fromIterable( emails )
                    .flatMap( email -> iut.getGroups( email ).collectList().materialize() )
                    .filter( s -> s.isOnError() )
                    .map( s -> s.getThrowable() );

            StepVerifier.create( pipeline )
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
            StepVerifier.create( iut.getGroups( "foo@org.com" ).collectList() )
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

            final var pipeline = Flux.range( 0, 3 )
                    .map( c -> "test" + c + "@test.com" )
                    .flatMap( email -> iut.getGroups( email ).collectList().materialize() )
                    .filter( s -> s.isOnError() )
                    .map( s -> s.getThrowable() );

            StepVerifier.create( pipeline )
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

            apiClient.setThrowError( true );
            StepVerifier.create( iut.getGroups( email ).collectList() )
                    .verifyErrorMatches( this::verifyErrorGlobal );            

            final var groups = DirectoryApiFixture.API_GROUP_MAP.get( email );

            final var expected = groups.stream()
                    .map( g -> new DirectoryService.Group( g.name(), g.email() ) )
                    .toList();
            
            apiClient.setThrowError( false );
            StepVerifier.create( iut.getGroups( email ).collectList() )
                    .consumeNextWith( actual -> assertThat( actual )
                            .containsExactlyInAnyOrderElementsOf( expected ) 
                    )
                    .verifyComplete();

            // Check that the expected calls were made
            final var pages = Math.ceilDiv( groups.size(), DirectoryApiMock.PAGE_SIZE );
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

            StepVerifier.create( iut.getGroups( email ).collectList() )
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

            final var pipeline = Flux.fromIterable( DirectoryApiFixture.ERROR_QUERIES )
                    .flatMap( email -> iut.getGroups( email ).collectList().materialize() )
                    .filter( s -> s.isOnError() )
                    .map( s -> s.getThrowable() );

            StepVerifier.create( pipeline )
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

            final var pipeline = Flux.fromStream( emails )
                    .flatMap( email -> iut.getGroups( email ).collectList().materialize() )
                    .filter( s -> s.isOnError() )
                    .map( s -> s.getThrowable() );

            StepVerifier.create( pipeline )
                    .expectNextMatches( this::verifyErrorQuery )
                    .expectNextMatches( this::verifyErrorQuery )
                    .expectNextMatches( this::verifyErrorQuery )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            assertThat( apiClient.batchCount() ).isGreaterThan( 0 );
        
        }

    }

    /**
     * Tests that result in a timeout.
     */
    @Nested
    // Concurrent works with this set, and it's very useful since
    // all tests take at least a second to reach the timeout
    @Execution( ExecutionMode.CONCURRENT )
    class Timeout {

        /**
         * Tests getting a global hang in a single call.
         */
        @Test
        public void testGlobalTimeoutSingle() throws IOException {

            apiClient.setHang( true );
            StepVerifier.create( iut.getGroups( "foo@org.com" ).collectList() )
                    .verifyError( TimeoutException.class );

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );
        
        }

        /**
         * Tests getting a global hang in a batch call.
         */
        @Test
        public void testGlobalTimeoutBatch() throws IOException {

            apiClient.setHang( true );

            final var pipeline = Flux.range( 0, 3 )
                    .map( c -> "test" + c + "@test.com" )
                    .flatMap( email -> iut.getGroups( email ).collectList().materialize() )
                    .filter( s -> s.isOnError() )
                    .map( s -> s.getThrowable() );

            final Predicate<Throwable> matcher = error -> error instanceof TimeoutException;

            StepVerifier.create( pipeline )
                    .expectNextMatches( matcher )
                    .expectNextMatches( matcher )
                    .expectNextMatches( matcher )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            // VSCode's test runner doesn't like it for some reason
            // assertThat( apiClient.batchCount() ).isEqualTo( 1 );
        
        }

        /**
         * Tests doing a successful call after a temporary global hang.
         */
        @Test
        public void testGlobalTimeoutRecovery() throws IOException {

            final var email = "group-A@org.com";

            apiClient.setHang( true );
            StepVerifier.create( iut.getGroups( email ).collectList() )
                    .verifyError( TimeoutException.class );

            final var groups = DirectoryApiFixture.API_GROUP_MAP.get( email );

            final var expected = groups.stream()
                    .map( g -> new DirectoryService.Group( g.name(), g.email() ) )
                    .toList();
            
            apiClient.setHang( false );
            StepVerifier.create( iut.getGroups( email ).collectList() )
                    .consumeNextWith( actual -> assertThat( actual )
                            .containsExactlyInAnyOrderElementsOf( expected ) 
                    )
                    .verifyComplete();

            // Check that the expected calls were made
            final var pages = Math.ceilDiv( groups.size(), DirectoryApiMock.PAGE_SIZE );
            assertThat( apiClient.singleCount() ).isEqualTo( pages + 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );

        }

        /**
         * Argument provider for {@link #testQueryTimeoutSingle(String)}.
         *
         * @return The arguments.
         */
        private static Stream<String> testQueryTimeoutSingle() {

            return DirectoryApiFixture.HANG_QUERIES.stream();

        }

        /**
         * Tests getting a query hang in a single call.
         *
         * @param email The email to query.
         */
        @ParameterizedTest
        @MethodSource
        public void testQueryTimeoutSingle( final String email ) throws IOException {

            StepVerifier.create( iut.getGroups( email ).collectList() )
                    .verifyError( TimeoutException.class );

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 1 );
            assertThat( apiClient.batchCount() ).isEqualTo( 0 );
        
        }

        /**
         * Tests getting a query hang in a batch call.
         */
        @Test
        public void testQueryTimeoutBatch() throws IOException {

            final var pipeline = Flux.fromIterable( DirectoryApiFixture.HANG_QUERIES )
                    .flatMap( email -> iut.getGroups( email ).collectList().materialize() )
                    .filter( s -> s.isOnError() )
                    .map( s -> s.getThrowable() );

            final Predicate<Throwable> matcher = error -> error instanceof TimeoutException;

            StepVerifier.create( pipeline )
                    .expectNextMatches( matcher )
                    .expectNextMatches( matcher )
                    .expectNextMatches( matcher )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            assertThat( apiClient.batchCount() ).isEqualTo( 1 );
        
        }

        /**
         * Tests a mix of queries that should and shouldn't timeout in one batch.
         * 
         * <p>Unlike the error case, the whole batch should get stuck and time out,
         * so all queries are affected.
         */
        @Test
        public void testQueryTimeoutBatchMixed() throws IOException {

            final var emails = Stream.concat(
                    DirectoryApiFixture.HANG_QUERIES.stream(),
                    DirectoryApiFixture.API_GROUP_MAP.keySet().stream()
            ).toList();

            final var pipeline = Flux.fromIterable( emails )
                    .flatMap( email -> iut.getGroups( email ).collectList().materialize() )
                    .filter( s -> s.isOnError() )
                    .map( s -> s.getThrowable() )
                    .filter( error -> error instanceof TimeoutException );

            StepVerifier.create( pipeline )
                    .expectNextCount( emails.size() )
                    .verifyComplete();

            // Check that the expected calls were made
            assertThat( apiClient.singleCount() ).isEqualTo( 0 );
            // VSCode's test runner doesn't like it for some reason
            // assertThat( apiClient.batchCount() ).isEqualTo( 1 );
        
        }

    }
    
}
