package dev.sympho.google_group_resolver.google;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Directory service configuration.
 */
@Configuration
public class ServiceConfiguration {

    /** Creates a new instance. */
    public ServiceConfiguration() {}

    /**
     * Creates the directory service.
     *
     * @param client The API client.
     * @param config The service configuration.
     * @param meters The meter registry to use.
     * @return The directory service.
     */
    @Bean
    DirectoryServiceProvider directory( 
            final DirectoryApi client, 
            final ServiceSettings config, 
            final MeterRegistry meters 
    ) {

        return new DirectoryServiceProvider(
                client, 
                config.batchSize(), 
                config.batchTimeout(),
                meters
        );

    }
    
}
