package dev.sympho.google_group_resolver.google;

import java.nio.file.Path;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API service account credentials settings.
 *
 * @param delegatedEmail The email of the admin account that delegated access.
 * @param path The path to the credentials JSON file.
 */
@ConfigurationProperties( ServiceSettings.PREFIX + ".credentials" )
public record ApiCredentials(
        @NotBlank String delegatedEmail,
        @NotNull Path path
) {}
