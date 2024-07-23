package dev.sympho.google_group_resolver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.sympho.google_group_resolver.google.DirectoryService;

/**
 * Configuration for the group resolver.
 */
@Configuration
public class ResolverConfiguration {

    /** Creates a new instance. */
    public ResolverConfiguration() {}

    /**
     * Creates the group cache.
     *
     * @param directory The directory service.
     * @param config The cache settings.
     * @return The group cache.
     */
    @Bean
    @ConditionalOnProperty( prefix = CacheSettings.PREFIX, name = "enabled", matchIfMissing = true )
    LRUGroupCache cache( final DirectoryService directory, final CacheSettings config ) {

        return new LRUGroupCache( 
                directory, 
                config.ttlValid(), 
                config.ttlStale(), 
                config.cleanerPeriod(), 
                config.capacity()
        );

    }

    /**
     * Creates a passthrough group cache.
     *
     * @param directory The directory service.
     * @return The group cache.
     */
    @Bean
    @ConditionalOnProperty( prefix = CacheSettings.PREFIX, name = "enabled", havingValue = "false" )
    PassthroughGroupCache passthruCache( final DirectoryService directory ) {

        return new PassthroughGroupCache( directory );

    }

    /**
     * Creates the cache seeder.
     *
     * @param directory The directory service.
     * @param cache The group cache.
     * @param config The cache settings.
     * @return The seeder.
     */
    @Bean
    @ConditionalOnProperty( 
            prefix = CacheSettings.PREFIX, 
            name = { "enabled", "seeder.enabled" }, 
            matchIfMissing = true
    )
    CacheSeeder seeder( 
            final DirectoryService directory, 
            final GroupCache cache, 
            final CacheSettings config 
    ) {

        return new CacheSeeder( directory, cache, config.seeder().period() );

    }

    /**
     * Creates the resolver.
     *
     * @param cache The group cache.
     * @param config The resolver settings.
     * @return The resolver.
     */
    @Bean
    RecursiveGroupResolver resolver( final GroupCache cache, final ResolverSettings config ) {

        return new RecursiveGroupResolver( cache, config.prefetch() );

    }
    
}
