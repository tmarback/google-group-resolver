package dev.sympho.google_group_resolver.google;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Provides interaction with the Workspaces Directory using the API.
 * 
 * <p>This provider bridges the gap between the syncronous service from the Google API libraries,
 * and the reactive interface.
 */
public class DirectoryServiceProvider implements DirectoryService {

    /** 
     * The maximum amount of time that the flux issued by {@link #getGroupsFor(String)} waits
     * without new signals before timing out.
     * 
     * <p>Normally this timeout should never be reached, as the underlying HTTP requests eventually
     * timeout by themselves, which will cause an error to be issued. This timeout is mostly a
     * preventative measure in case some unexpected issue causes the task/emitter to be lost,
     * leading to the task never finishing.
     */
    static final Duration RESULT_TIMEOUT = Duration.ofSeconds( 10 );

    /** Error message used when an unexpected exception is thrown. */
    static final String ERROR_UNEXPECTED_EXCEPTION = 
            "An unexpected error was encountered while fetching groups";

    /** Logger. */
    private static final Logger LOG = LoggerFactory.getLogger( DirectoryServiceProvider.class );

    /** The maximum amount of tasks to buffer while handling isn't active. */
    private static final int TASK_INITIAL_BUFFER_SIZE = 1000;
    /** The maximum amount of tasks to buffer due to backpressure before dropping tasks. */
    private static final int TASK_BUFFER_SIZE = 1000;

    /** The API client. */
    private final DirectoryApi client;

    /** The maximum amount of requests in a batch. */
    private final int batchSize;
    /** 
     * The maximum amount of time to wait to collect batch entries, 
     * before continuing with a partially-filled batch. 
     */
    private final Duration batchTimeout;

    /** The sink used to issue tasks. */
    private final Sinks.Many<Task<?>> taskSink = Sinks.many()
            .multicast()
            .onBackpressureBuffer( TASK_INITIAL_BUFFER_SIZE, false );

    /**
     * The scheduler used to handle API requests.
     * 
     * <p>This scheduler must be capable of handling blocking network requests.
     */
    private final Scheduler requestScheduler = Schedulers.boundedElastic();
    /**
     * The scheduler used to issue resulting signals.
     * 
     * <p>This scheduler will <b>not</b> be used for blocking requests.
     */
    private final Scheduler responseScheduler = Schedulers.parallel();
    /**
     * The scheduler used when submitting new tasks to the queue.
     * 
     * <p>It must be single-threaded, as the task sink requires serialized access.
     */
    private final Scheduler taskSubmitScheduler = Schedulers.single();
    /**
     * The scheduler used to process tasks before starting a request.
     * 
     * <p>Mainly to free up {@link #taskSubmitScheduler} once serialized access
     * is no longer necessary.
     */
    private final Scheduler taskProcessScheduler = Schedulers.parallel();

    /** The running task handler. */
    private @Nullable Disposable running;

    /**
     * Creates a new instance.
     *
     * @param client The API client to use.
     * @param batchSize The maximum amount of requests to have in a batch.
     * @param batchTimeout The maximum amount of time to wait to collect batch entries, 
     *                     before continuing with a partially-filled batch. 
     */
    public DirectoryServiceProvider( 
            final DirectoryApi client,
            final int batchSize,
            final Duration batchTimeout
    ) {

        this.client = client;
        this.batchSize = batchSize;
        this.batchTimeout = batchTimeout;

    }

    /**
     * Execute a batch of tasks.
     *
     * @param tasks The tasks to execute.
     */
    private void doTasks( final List<Task<?>> tasks ) {

        if ( tasks.isEmpty() ) { // Sanity check
            LOG.warn( "Empty batch received" );
            return;
        }

        if ( tasks.size() == 1 ) {
            // Shortcut to direct call if there is only one
            final var task = tasks.get( 0 );
            LOG.debug( "Issuing standalone request for {}", task );
            client.makeRequest( task.toRequest() );
            LOG.trace( "Standalone request for {} done", task );
        } else {
            LOG.debug( "Issuing batch request for {} tasks", tasks.size() );
            // Checker is just being weird
            @SuppressWarnings( { "nullness:return", "signedness:return" } ) 
            final var requests = tasks.stream()
                    .map( t -> t.toRequest() )
                    .toList();
            client.makeRequestBatch( requests );
            LOG.trace( "Batch request done ({})", tasks.size() );
        }

    }

    /**
     * Start handling submitted requests.
     */
    @PostConstruct
    public synchronized void start() {

        if ( this.running == null ) {
            LOG.info( "Starting directory API client" );
            this.running = taskSink.asFlux()
                    .publishOn( taskProcessScheduler )
                    .onBackpressureBuffer( 
                        TASK_BUFFER_SIZE, 
                        // Signal error on any dropped tasks
                        task -> task.emitter().error( new IllegalStateException(
                            "Directory API task buffer overflow"
                        ) ), 
                        // Don't kill the stream on backpressure issues
                        // Drop oldest since it has a higher chance of being near a timeout anyway
                        BufferOverflowStrategy.DROP_OLDEST 
                    )
                    .doOnNext( t -> LOG.trace( "Task {} received", t ) )
                    .bufferTimeout( batchSize, batchTimeout )
                    .doOnNext( t -> LOG.trace(
                            "Issuing batch with {} tasks",
                            t.size()
                    ) )
                    .publishOn( requestScheduler )
                    .doOnNext( this::doTasks )
                    .repeat()
                    .subscribe();
        }

    }

    /**
     * Stop handling submitted requests.
     */
    @PreDestroy
    public synchronized void stop() {
        
        if ( this.running != null ) {
            LOG.info( "Stopping directory API client" );
            this.running.dispose();
            this.running = null;
        }

    }

    /**
     * Submits a task for handling.
     *
     * @param task The task to submit.
     */
    private void submitTask( final Task<?> task ) {

        // Needs to be done on a single-threaded scheduler
        // since the sink needs serialized access
        Mono.just( task )
                .publishOn( taskSubmitScheduler )
                .doOnNext( t -> LOG.trace( "Submitting task {}", t ) )
                .map( this.taskSink::tryEmitNext )
                // Error handling
                .filter( result -> result != EmitResult.OK )
                .map( result -> "Could not submit task: " + result )
                .map( IllegalStateException::new )
                .subscribe( task.emitter()::error );

    }

    /**
     * Submits a task for handling.
     *
     * @param <V> The type of task result values.
     * @param taskFactory The factory to use to create a task from the sink.
     * @return The task results.
     */
    private <V extends @NonNull Object> Flux<V> submitTask( 
            final Function<FluxSink<V>, Task<V>> taskFactory
    ) {

        return Flux.<V>push( emitter -> submitTask( 
                        taskFactory.apply( emitter ) 
                ) )
                .publishOn( responseScheduler )
                .timeout( RESULT_TIMEOUT ); // Timeout in case somehow the task gets lost

    }

    @Override
    public Flux<DirectoryGroup> getGroupsFor( final String email ) {

        // Submit group fetch as a task
        return this.<DirectoryGroup>submitTask( 
                        emitter -> new GroupMembershipTask( this, email, emitter, null ) 
                )
                .checkpoint( "Get group memberships" );

    }

    @Override
    public Flux<DirectoryGroup> getGroups() {

        // Submit group fetch as a task
        return this.<DirectoryGroup>submitTask( 
                        emitter -> new GroupListTask( this, emitter, null ) 
                )
                .checkpoint( "Get group list" );

    }

    /**
     * A task to be executed.
     *
     * @param <V> The type of task result values.
     */
    private interface Task<V extends @NonNull Object> 
            extends DirectoryApi.Callback<DirectoryApi.ListResult<V>> {

        /**
         * Converts the task to an API request.
         *
         * @return The request.
         */
        @SideEffectFree
        DirectoryApi.Request<DirectoryApi.ListResult<V>> toRequest();

        /**
         * The provider that is running the task.
         *
         * @return The provider.
         */
        @Pure
        DirectoryServiceProvider provider();

        /**
         * The emitter to use to issue results.
         *
         * @return The emitter.
         */
        @Pure
        FluxSink<V> emitter();

        /**
         * The token of the result page to fetch.
         *
         * @return The page token.
         */
        @Pure
        @Nullable String pageToken();

        /**
         * Creates a copy of this task with a new page token.
         *
         * @param nextPageToken The page token to insert.
         * @return The new task.
         */
        @SideEffectFree 
        Task<V> page( String nextPageToken );

        @Override
        default void onSuccess( final DirectoryApi.ListResult<V> result ) {

            LOG.trace( "Successful result for {}", this );

            if ( emitter().isCancelled() ) {
                LOG.trace( "Request was cancelled" );
                return; // Don't need to continue if request was cancelled
            }

            result.values().forEach( emitter()::next );

            final var nextToken = result.nextPageToken();
            if ( nextToken == null ) {
                // No more pages, complete the result flux
                emitter().complete();
            } else if ( !emitter().isCancelled() ) { // Check again just in case
                // Issue task for the next page
                provider().submitTask( page( nextToken ) );
            }
            
        }

        @Override
        default void onFailure( final DirectoryApi.RequestFailedException error ) {

            LOG.trace( "Failure result for {}", this );

            emitter().error( new FailedException( error ) );

        }

        @Override
        default void onError( final Exception error ) {

            LOG.trace( "Error result for {}: {}", this, error );

            final var message = ERROR_UNEXPECTED_EXCEPTION;
            emitter().error( new IllegalStateException( message, error ) );

        }

    }

    /**
     * A group membership fetch task.
     *
     * @param provider The provider that is running the task.
     * @param email The user/group email.
     * @param emitter The emitter to send results to.
     * @param pageToken The token to use for fetching the next page of results, if any.
     */
    private record GroupMembershipTask(
            DirectoryServiceProvider provider,
            String email,
            FluxSink<DirectoryGroup> emitter,
            @Nullable String pageToken
    ) implements Task<DirectoryGroup> {

        @Override
        public DirectoryApi.Request<DirectoryApi.ListResult<DirectoryGroup>> toRequest() {

            return new DirectoryApi.GroupMembershipRequest( email, pageToken, this );

        }

        @Override
        public Task<DirectoryGroup> page( final String nextPageToken ) {

            return new GroupMembershipTask( provider, email, emitter, nextPageToken );

        }

        @Override
        public String toString() {

            return "GroupMembershipTask[email=%s, pageToken=%s]".formatted( 
                    email, 
                    Objects.requireNonNullElse( pageToken, "null" ) 
            );

        }

    }

    /**
     * A group list fetch task.
     *
     * @param provider The provider that is running the task.
     * @param emitter The emitter to send results to.
     * @param pageToken The token to use for fetching the next page of results, if any.
     */
    private record GroupListTask(
            DirectoryServiceProvider provider,
            FluxSink<DirectoryGroup> emitter,
            @Nullable String pageToken
    ) implements Task<DirectoryGroup> {

        @Override
        public DirectoryApi.Request<DirectoryApi.ListResult<DirectoryGroup>> toRequest() {

            return new DirectoryApi.GroupListRequest( pageToken, this );

        }

        @Override
        public Task<DirectoryGroup> page( final String nextPageToken ) {

            return new GroupListTask( provider, emitter, nextPageToken );

        }

        @Override
        public String toString() {

            return "GroupListTask[pageToken=%s]".formatted( 
                    Objects.requireNonNullElse( pageToken, "null" ) 
            );

        }

    }
    
}
