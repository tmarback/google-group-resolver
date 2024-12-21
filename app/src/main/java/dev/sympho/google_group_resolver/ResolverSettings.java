package dev.sympho.google_group_resolver;

import jakarta.validation.Valid;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import dev.sympho.google_group_resolver.google.ServiceSettings;

/**
 * Resolver settings.
 *
 * @param directory The directory service settings.
 * @param cache The cache settings
 * @param prefetch If {@code true}, enables prefetching.
 */
@Validated
@ConfigurationProperties( "resolver" )
public record ResolverSettings(
        @NestedConfigurationProperty @Valid @DefaultValue ServiceSettings directory,
        @NestedConfigurationProperty @Valid @DefaultValue CacheSettings cache,
        @DefaultValue( "true" ) boolean prefetch
) {}
