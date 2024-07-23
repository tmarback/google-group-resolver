package dev.sympho.google_group_resolver;

import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Collections;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.DirectoryScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.sympho.google_group_resolver.google.DirectoryApi;
import dev.sympho.google_group_resolver.google.DirectoryApiClient;
import dev.sympho.google_group_resolver.google.DirectoryService;
import dev.sympho.google_group_resolver.google.DirectoryServiceProvider;

/**
 * Configuration for the group resolver.
 */
@Configuration
public class ResolverConfiguration {

    /** Necessary API scopes. */
    private static final Collection<String> API_SCOPES = Collections.singleton( 
            DirectoryScopes.ADMIN_DIRECTORY_GROUP_READONLY 
    );

    /** The config properties. */
    final Config config;

    /**
     * Creates a new instance.
     *
     * @param config The configuration to use.
     */
    public ResolverConfiguration( final Config config ) {

        this.config = config;

    }

    /**
     * Creates the Directory API client.
     *
     * @return The API client.
     * @throws IOException if an error ocurred.
     * @throws GeneralSecurityException if an error ocurred.
     * @throws IllegalArgumentException if the configuration is invalid.
     */
    @Bean
    // Don't create if running integration tests, as those use a mock
    @ConditionalOnMissingClass( "dev.sympho.google_group_resolver.ApplicationTests" )
    DirectoryApiClient client() 
            throws IOException, GeneralSecurityException, IllegalArgumentException {

        final var creds = config.client().credentials();
        if ( !creds.path().toFile().exists() ) {
            throw new IllegalArgumentException( "Credentials file not found: " + creds.path() );
        }

        final GoogleCredentials credential;
        try ( var is = Files.newInputStream( creds.path() ) ) {
            credential = ServiceAccountCredentials.fromStream( is )
                    .createScoped( API_SCOPES )
                    .createDelegated( creds.delegatedEmail() );
        }

        final var transport = GoogleNetHttpTransport.newTrustedTransport();
        final var factory = GsonFactory.getDefaultInstance();
        final var initializer = new HttpCredentialsAdapter( credential );

        final Directory client = new Directory.Builder( transport, factory, initializer )
                .setApplicationName( "group-resolver" )
                .build();

        return new DirectoryApiClient( client );

    }

    /**
     * Creates the directory service.
     *
     * @param client The API client.
     * @return The directory service.
     */
    @Bean
    DirectoryServiceProvider directory( final DirectoryApi client ) {

        return new DirectoryServiceProvider(
                client, 
                config.client().batchSize(), 
                config.client().batchTimeout()
        );

    }

    /**
     * Creates the group cache.
     *
     * @param directory The directory service.
     * @return The group cache.
     */
    @Bean
    GroupCache cache( final DirectoryService directory ) {

        if ( config.cache().disable() ) {
            return new PassthroughGroupCache( directory );
        }

        return new LRUGroupCache( 
                directory, 
                config.cache().ttlValid(), 
                config.cache().ttlStale(), 
                config.cache().cleanerPeriod(), 
                config.cache().capacity()
        );

    }

    /**
     * Creates the cache seeder.
     *
     * @param directory The directory service.
     * @param cache The group cache.
     * @return The seeder.
     */
    @Bean
    CacheSeeder seeder( final DirectoryService directory, final GroupCache cache ) {

        final var settings = config.cache().seeder();
        final var enabled = settings.enabled() && !config.cache().disable();
        return new CacheSeeder( directory, cache, settings.period(), enabled );

    }

    /**
     * Creates the resolver.
     *
     * @param cache The group cache.
     * @return The resolver.
     */
    @Bean
    RecursiveGroupResolver resolver( final GroupCache cache ) {

        return new RecursiveGroupResolver( cache, config.prefetch() );

    }
    
}
