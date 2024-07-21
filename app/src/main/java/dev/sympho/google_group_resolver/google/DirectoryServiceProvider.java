package dev.sympho.google_group_resolver.google;

import java.time.Duration;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.checkerframework.checker.nullness.qual.Nullable;
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
@SuppressWarnings( "IllegalCatch" ) // Need to catch Exception to prevent task leaks
public class DirectoryServiceProvider implements DirectoryService {

    /** 
     * The maximum amount of time that the flux issued by {@link #getGroups(String)} waits
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
    private final Sinks.Many<Task> taskSink = Sinks.many()
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
     * Submits a task for handling.
     *
     * @param task The task to submit.
     */
    private void submitTask( final Task task ) {

        // Needs to be done on a single-threaded scheduler
        // since the sink needs serialized access
        Mono.just( task )
                .publishOn( taskSubmitScheduler )
                .doOnNext( t -> LOG.trace( 
                        "Submitting task for {}:{}", 
                        t.email(), t.nextPageToken()
                ) )
                .map( this.taskSink::tryEmitNext )
                // Error handling
                .filter( result -> result != EmitResult.OK )
                .map( result -> "Could not submit task: " + result )
                .map( IllegalStateException::new )
                .subscribe( task.emitter::error );

    }

    /**
     * Handles a request result.
     *
     * @param request The originating request.
     * @param result The received result.
     */
    private void handleResult( final Task request, final DirectoryApi.Result result ) {

        LOG.trace( "Successful result for {}:{}", request.email(), request.nextPageToken() );

        if ( request.emitter().isCancelled() ) {
            LOG.trace( "Request was cancelled" );
            return; // Don't need to continue if request was cancelled
        }

        result.groups()
                .map( group -> new Group( group.name(), group.email() ) )
                .forEach( request.emitter()::next );

        final var nextToken = result.nextPageToken();
        if ( nextToken == null ) {
            // No more pages, complete the result flux
            request.emitter().complete();
        } else if ( !request.emitter().isCancelled() ) { // Check again just in case
            // Issue task for the next page
            submitTask( new Task( request.email(), request.emitter(), nextToken ) );
        }

    }

    /**
     * Handles a request failure.
     *
     * @param request The originating request.
     * @param error The received error.
     */
    private void handleFailure( 
            final Task request, 
            final DirectoryApi.RequestFailedException error 
    ) {

        LOG.trace( "Failure result for {}:{}", request.email(), request.nextPageToken() );

        request.emitter().error( new FailedException( error ) );

    }

    /**
     * Handles an unexpected error during a request.
     *
     * @param request The originating request.
     * @param error The received error.
     */
    private void handleError( final Task request, final Exception error ) {

        LOG.trace( "Error result for {}:{}: {}", request.email(), request.nextPageToken(), error );

        final var message = ERROR_UNEXPECTED_EXCEPTION;
        request.emitter().error( new IllegalStateException( message, error ) );

    }

    /**
     * Execute a batch of tasks.
     *
     * @param tasks The tasks to execute.
     */
    private void doTasks( final List<Task> tasks ) {

        if ( tasks.isEmpty() ) { // Sanity check
            LOG.warn( "Empty batch received" );
            return;
        }

        if ( tasks.size() == 1 ) {
            // Shortcut to direct call if there is only one
            final var task = tasks.get( 0 );
            LOG.debug( "Issuing standalone request for {}:{}", task.email(), task.nextPageToken() );
            try {
                final var result = client.getGroups( task.email(), task.nextPageToken() );
                handleResult( task, result );
            } catch ( final DirectoryApi.RequestFailedException ex ) {
                handleFailure( task, ex );
            } catch ( final Exception ex ) {
                LOG.error( "Standalone request threw unexpected exception", ex );
                handleError( task, ex );
            }
            LOG.trace( "Standalone request for {}:{} done", task.email(), task.nextPageToken() );
            return;
        }

        LOG.debug( "Issuing batch request for {} tasks", tasks.size() );

        // Prepare batch requests
        final var requests = tasks.stream().map( task -> new DirectoryApi.BatchRequest(
                task.email(), 
                task.nextPageToken(), 
                new Callback( this, task )
        ) ).toList();

        try {
            // Execute batch
            client.getGroupsBatch( requests );
        } catch ( final Exception ex ) {
            LOG.error( "Batch request threw unexpected exception", ex );
            tasks.forEach( task -> handleError( task, ex ) );
        }

        LOG.trace( "Batch request done", tasks.size() );

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
                    .doOnNext( t -> LOG.trace(
                            "Task for {}:{} received",
                            t.email(), t.nextPageToken()
                    ) )
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

    @Override
    public Flux<Group> getGroups( final String email ) {

        // Submit group fetch as a task
        return Flux.<Group>push( emitter -> submitTask( new Task( email, emitter, null ) ) )
                .publishOn( responseScheduler )
                .timeout( RESULT_TIMEOUT ) // Timeout in case somehow the task gets lost
                .checkpoint( "Get groups" );

    }

    /**
     * A group membership fetch task.
     *
     * @param email The user/group email.
     * @param emitter The emitter to send results to.
     * @param nextPageToken The token to use for fetching the next page of results, if any.
     */
    private record Task(
            String email,
            FluxSink<Group> emitter,
            @Nullable String nextPageToken
    ) {}

    /**
     * A callback for a request within a batch request.
     *
     * @param provider The underlying provider.
     * @param task The task that originated the request.
     */
    private record Callback(
            DirectoryServiceProvider provider,
            Task task
    ) implements DirectoryApi.Callback {

        @Override
        public void onSuccess( final DirectoryApi.Result result ) {

            provider.handleResult( task, result );
            
        }

        @Override
        public void onFailure( final DirectoryApi.RequestFailedException error ) {

            provider.handleFailure( task, error );

        }

        @Override
        public void onError( final Exception error ) {

            provider.handleError( task, error );

        }

    }
    
}
