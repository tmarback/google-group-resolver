package dev.sympho.google_group_resolver.google;

import java.time.Duration;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API client settings.
 *
 * @param batchSize The maximum amount of requests to include in the same batch.
 * @param batchTimeout The maximum amount of time to wait to accumulate requests for a batch.
 */
public record ServiceSettings(
        @DefaultValue( "1000" ) int batchSize,
        @DefaultValue( "1ms" ) Duration batchTimeout
) {}
