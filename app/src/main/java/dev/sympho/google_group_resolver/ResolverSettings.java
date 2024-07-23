package dev.sympho.google_group_resolver;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Resolver settings.
 *
 * @param prefetch If {@code true}, enables prefetching.
 */
@Validated
@ConfigurationProperties( ResolverSettings.PREFIX )
public record ResolverSettings(
        @DefaultValue( "true" ) boolean prefetch
) {

    /** The prefix for all settings. */
    public static final String PREFIX = "resolver";

}
