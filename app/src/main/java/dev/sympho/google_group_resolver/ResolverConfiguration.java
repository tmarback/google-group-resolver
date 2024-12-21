package dev.sympho.google_group_resolver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.sympho.google_group_resolver.google.DirectoryService;
import dev.sympho.google_group_resolver.google.ServiceSettings;

/**
 * Configuration for the group resolver.
 */
@Configuration
public class ResolverConfiguration {

    /** Creates a new instance. */
    public ResolverConfiguration() {}

    /**
     * Extracts the directory service settings.
     *
     * @param config The resolver settings
     * @return The directory service settings.
     */
    @Bean
    ServiceSettings directorySettings( final ResolverSettings config ) {
        return config.directory();
    }

    /**
     * Extracts the cache settings.
     *
     * @param config The resolver settings
     * @return The cache settings.
     */
    @Bean
    CacheSettings cacheSettings( final ResolverSettings config ) {
        return config.cache();
    }

    /**
     * Creates the group cache.
     *
     * @param directory The directory service.
     * @param config The cache settings.
     * @return The group cache.
     */
    @Bean
    GroupCache cache( final DirectoryService directory, final CacheSettings config ) {

        if ( config.enabled() ) {
            return new LRUGroupCache( 
                    directory, 
                    config.ttlValid(), 
                    config.ttlStale(), 
                    config.cleanerPeriod(), 
                    config.capacity()
            );
        } else {
            return new PassthroughGroupCache( directory );
        }

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
    CacheSeeder seeder( 
            final DirectoryService directory, 
            final GroupCache cache, 
            final CacheSettings config 
    ) {

        return new CacheSeeder( directory, cache, config.seeder() );

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
