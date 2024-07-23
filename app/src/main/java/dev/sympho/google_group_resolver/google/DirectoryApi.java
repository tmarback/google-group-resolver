package dev.sympho.google_group_resolver.google;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

/**
 * Interface for accessing the Workspaces Directory API.
 */
public interface DirectoryApi {

    /**
     * Makes a request to the API.
     *
     * @param request The request to make.
     */
    void makeRequest( Request<?> request );

    /**
     * Makes multiple requests to the API in a batch.
     *
     * @param requests The requests to make.
     */
    void makeRequestBatch( Collection<? extends Request<?>> requests );

    /**
     * A result returned by the API.
     */
    sealed interface Result permits ListResult {}

    /**
     * A result that contains a sequence of data.
     *
     * @param <V> The data type.
     * @param values The values.
     * @param nextPageToken The token to use to fetch the next page of data, or {@code null} if
     *                      there are no more pages.
     */
    record ListResult<V extends @NonNull Object>(
            List<V> values,
            @Nullable String nextPageToken
    ) implements Result {}

    /**
     * Callback for handling the result of a request.
     *
     * @param <R> The result type.
     */
    interface Callback<R extends Result> {

        /**
         * Invoked if the request was successful.
         *
         * @param result The result of the request.
         */
        void onSuccess( R result );

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
     * A request to the API.
     *
     * @param <R> The result type.
     */
    sealed interface Request<R extends Result> permits GroupMembershipRequest, GroupListRequest {

        /**
         * The callback to invoke to handle the result of the request.
         *
         * @return The callback.
         */
        @Pure
        Callback<R> callback();

    }

    /**
     * A request to fetch the groups that an entity is a member of.
     *
     * @param email The email of the user/group.
     * @param nextPageToken The token for the next page received by a previous request, or
     *                      {@code null} if this is the first request.
     * @param callback The callback to invoke for handling the result.
     * @see DirectoryApi#makeRequest(Request)
     * @see DirectoryApi#makeRequestBatch(Collection)
     */
    record GroupMembershipRequest(
            String email,
            @Nullable String nextPageToken,
            Callback<ListResult<DirectoryGroup>> callback
    ) implements Request<ListResult<DirectoryGroup>> {

        @Override
        public String toString() {

            return "GroupMembershipRequest[email=%s, nextPageToken=%s]".formatted( 
                    email, 
                    Objects.requireNonNullElse( nextPageToken, "null" )
            );

        }

    }

    /**
     * A request to fetch the groups that are part of the directory.
     *
     * @param nextPageToken The token for the next page received by a previous request, or
     *                      {@code null} if this is the first request.
     * @param callback The callback to invoke for handling the result.
     * @see DirectoryApi#makeRequest(Request)
     * @see DirectoryApi#makeRequestBatch(Collection)
     */
    record GroupListRequest(
            @Nullable String nextPageToken,
            Callback<ListResult<DirectoryGroup>> callback
    ) implements Request<ListResult<DirectoryGroup>> {

        @Override
        public String toString() {

            return "GroupListRequest[nextPageToken=%s]".formatted( 
                    Objects.requireNonNullElse( nextPageToken, "null" )
            );

        }

    }

    /**
     * Error representing a failed fetch request.
     */
    class RequestFailedException extends RuntimeException {

        private static final long serialVersionUID = -610260442592637846L;

        /** The response status code. */
        public final int code;

        /** The response error message. */
        public final @Nullable String message;

        /**
         * Creates a new instance.
         *
         * @param code The response status code.
         * @param message The error message, if present.
         */
        public RequestFailedException( final int code, final @Nullable String message ) {

            super( "Request failed (%d): %s".formatted( code, message == null ? "" : message ) );

            this.code = code;
            this.message = message;

        }

    }
    
}
