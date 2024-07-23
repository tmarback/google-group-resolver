package dev.sympho.google_group_resolver;

import java.time.Duration;

import jakarta.validation.Valid;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cache settings.
 *
 * @param enabled Whether the cache should be enabled.
 * @param capacity The cache capacity.
 * @param ttlValid How long entries should remain valid before becoming stale.
 * @param ttlStale How long entries should remain stale before becoming expired.
 * @param cleanerPeriod Period between runs of the cleaner job.
 * @param seeder The seeder configuration.
 */
@ConfigurationProperties( CacheSettings.PREFIX )
public record CacheSettings(
        @DefaultValue( "true" ) boolean enabled,
        @DefaultValue( "1000" ) int capacity,
        @DefaultValue( "1m" ) Duration ttlValid,
        @DefaultValue( "2d" ) Duration ttlStale,
        @DefaultValue( "1m" ) Duration cleanerPeriod,
        @Valid @DefaultValue Seeder seeder
) {

    /** The prefix for all settings. */
    public static final String PREFIX = ResolverSettings.PREFIX + ".cache";

    /**
     * Seeder settings.
     *
     * @param enabled Whether to enable the seeder.
     * @param period The period for re-seeding. Set to 0 to disable.
     */
    public record Seeder(
            @DefaultValue( "true" ) boolean enabled,
            @DefaultValue( "1d" ) Duration period
    ) {}

}
