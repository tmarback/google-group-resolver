package dev.sympho.google_group_resolver.google;

import java.nio.file.Path;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * API service account credentials settings.
 *
 * @param delegatedEmail The email of the admin account that delegated access.
 * @param path The path to the credentials JSON file.
 */
@Validated
@ConfigurationProperties( "credentials.google" )
public record ApiCredentials(
    @NotBlank String delegatedEmail,
    @NotNull Path path
) {}
