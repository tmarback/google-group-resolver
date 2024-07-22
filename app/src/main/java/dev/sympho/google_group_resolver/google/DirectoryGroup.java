package dev.sympho.google_group_resolver.google;

/**
 * A group in Workspaces.
 *
 * @param name The group name.
 * @param email The group email.
 */
public record DirectoryGroup(
        String name,
        String email
) {}
