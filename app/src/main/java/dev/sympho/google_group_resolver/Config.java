package dev.sympho.google_group_resolver;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration.
 *
 * @param cache The cache configuration.
 * @param prefetch If {@code true}, enables prefetching.
 * @param client The API client configuration.
 */
@Validated
@ConfigurationProperties( "groupresolver" )
public record Config(
        @Valid @DefaultValue CacheSettings cache,
        @DefaultValue( "true" ) boolean prefetch,
        @Valid @NotNull ClientSettings client
) {

    /**
     * Cache settings.
     *
     * @param disable If {@code true}, disables the cache.
     * @param capacity The cache capacity.
     * @param ttlValid How long entries should remain valid before becoming stale.
     * @param ttlStale How long entries should remain stale before becoming expired.
     * @param cleanerPeriod Period between runs of the cleaner job.
     */
    public record CacheSettings(
            @DefaultValue( "false" ) boolean disable,
            @DefaultValue( "1000" ) int capacity,
            @DefaultValue( "1m" ) Duration ttlValid,
            @DefaultValue( "30d" ) Duration ttlStale,
            @DefaultValue( "1m" ) Duration cleanerPeriod
    ) {}

    /**
     * API client settings.
     *
     * @param batchSize The maximum amount of requests to include in the same batch.
     * @param batchTimeout The maximum amount of time to wait to accumulate requests for a batch.
     * @param credentials The credentials to use to authenticate with Workspace.
     */
    public record ClientSettings(
            @DefaultValue( "1000" ) int batchSize,
            @DefaultValue( "1ms" ) Duration batchTimeout,
            @Valid @NotNull ClientCredentials credentials
    ) {}

    /**
     * API service account credentials settings.
     *
     * @param delegatedEmail The email of the admin account that delegated access.
     * @param path The path to the credentials JSON file.
     */
    public record ClientCredentials(
            @NotBlank String delegatedEmail,
            @NotNull Path path
    ) {}

}
