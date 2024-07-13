package dev.sympho.google_group_resolver.google;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Stream;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Interface for accessing the Workspaces Directory API.
 */
public interface DirectoryApi {

    /**
     * Retrieves the groups that a user or group is member of, including indirect memberships.
     *
     * @param email The email of the user/group.
     * @param nextPageToken The token for the next page received by a previous request, or
     *                      {@code null} if this is the first request.
     * @return The retrieval result.
     * @throws IOException if an expected error ocurred.
     * @throws RequestFailedException if the request failed.
     * @see Result#nextPageToken()
     * @see #getGroupsBatch(Collection)
     */
    Result getGroups( 
            String email, 
            @Nullable String nextPageToken 
    ) throws IOException, RequestFailedException;

    /**
     * Performs multiple instances of {@link #getGroups(String, String)} using a batch request.
     *
     * @param requests The requests to make in the batch request.
     * @throws IllegalArgumentException if the number of requests exceeds the maximum.
     * @see #getGroups(String, String)
     */
    void getGroupsBatch( Collection<BatchRequest> requests ) throws IllegalArgumentException;

    /**
     * A group in the Workspaces directory.
     *
     * @param name The group name.
     * @param email The group email.
     */
    record Group(
            String name,
            String email
    ) {}

    /**
     * The result of a group membership fetch request.
     * 
     * <p>One instance represents one page; there may be more results.
     *
     * @param groups The groups.
     * @param nextPageToken The token to use to fetch the next page of requests.
     *                      If there are no further results, {@code null}.
     */
    record Result(
            Stream<Group> groups,
            @Nullable String nextPageToken
    ) {}

    /**
     * A request to be executed as part of a batch.
     *
     * @param email The email of the user/group.
     * @param nextPageToken The token for the next page received by a previous request, or
     *                      {@code null} if this is the first request.
     * @param callback The callback to invoke for handling the result.
     * @see DirectoryApi#getGroups(String, String)
     */
    record BatchRequest(
            String email,
            @Nullable String nextPageToken,
            Callback callback
    ) {}

    /**
     * Callback for an individual request within a batch request.
     */
    interface Callback {

        /**
         * Invoked if the request was successful.
         *
         * @param result The result of the request.
         */
        void onSuccess( Result result );

        /**
         * Invoked if the request failed.
         *
         * @param error The error.
         */
        void onFailure( RequestFailedException error );

        /**
         * Invoked if an unexpected error was encountered.
         *
         * @param error The error.
         */
        void onError( Exception error );

    }

    /**
     * Error representing a failed fetch request.
     */
    class RequestFailedException extends RuntimeException {

        private static final long serialVersionUID = -610260442592637846L;

        /** The response status code. */
        public final int code;

        /**
         * Creates a new instance.
         *
         * @param code The response status code.
         * @param message The error message, if present.
         */
        public RequestFailedException( final int code, final @Nullable String message ) {

            super( message );

            this.code = code;

        }

    }
    
}
