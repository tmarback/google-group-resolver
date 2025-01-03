package dev.sympho.google_group_resolver.google;

import java.time.Duration;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API client settings.
 *
 * @param batchSize The maximum amount of requests to include in the same batch.
 * @param batchTimeout The maximum amount of time to wait to accumulate requests for a batch.
 * @param requestConcurrency The maximum number of concurrent inflight requests.
 */
public record ServiceSettings(
    @DefaultValue( "100" ) @Min( 1 ) int batchSize,
    @DefaultValue( "1ms" ) Duration batchTimeout,
    @DefaultValue( "5" ) @Min( 1 ) int requestConcurrency
) {}
