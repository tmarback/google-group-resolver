package dev.sympho.google_group_resolver.google;

import reactor.core.publisher.Flux;

/**
 * Service for interacting with Google Workspaces Directory.
 */
public interface DirectoryService {

    /**
     * Retrieves the groups that a user or group is member of, including indirect memberships.
     *
     * @param email The email of the user/group.
     * @return The groups.
     */
    Flux<DirectoryGroup> getGroupsFor( String email );

    /**
     * Retrieve all groups in the directory.
     *
     * @return The groups.
     */
    Flux<DirectoryGroup> getGroups();

    /**
     * Error indicating that an operation failed.
     */
    class FailedException extends RuntimeException {

        private static final long serialVersionUID = 25735252935L;

        /** The underlying API error. */
        private final DirectoryApi.RequestFailedException cause;

        /**
         * Creates a new instance.
         *
         * @param cause The underlying API error.
         */
        public FailedException( final DirectoryApi.RequestFailedException cause ) {

            super( cause.getMessage(), cause );

            this.cause = cause;

        }

        /**
         * The status code returned by the API.
         *
         * @return The status code.
         */
        public int code() {

            return this.cause.code;

        }

        /**
         * The error message.
         *
         * @return The message.
         */
        public String message() {

            return this.cause.message == null ? "" : this.cause.message;

        }

    }
    
}
