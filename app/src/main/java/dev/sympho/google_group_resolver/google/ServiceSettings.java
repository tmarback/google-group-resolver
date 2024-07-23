package dev.sympho.google_group_resolver.google;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import dev.sympho.google_group_resolver.ResolverSettings;

/**
 * API client settings.
 *
 * @param batchSize The maximum amount of requests to include in the same batch.
 * @param batchTimeout The maximum amount of time to wait to accumulate requests for a batch.
 */
@ConfigurationProperties( ServiceSettings.PREFIX )
public record ServiceSettings(
        @DefaultValue( "1000" ) int batchSize,
        @DefaultValue( "1ms" ) Duration batchTimeout
) {

    /** The prefix for all settings. */
    public static final String PREFIX = ResolverSettings.PREFIX + ".directory";

}
