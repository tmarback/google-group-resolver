package dev.sympho.google_group_resolver.google;

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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API client configuration.
 */
@Configuration
public class ApiConfiguration {

    /** Necessary API scopes. */
    private static final Collection<String> API_SCOPES = Collections.singleton( 
            DirectoryScopes.ADMIN_DIRECTORY_GROUP_READONLY 
    );

    /** Creates a new instance. */
    public ApiConfiguration() {}

    /**
     * Creates the Directory API client.
     *
     * @param creds The API credentials.
     * @return The API client.
     * @throws IOException if an error ocurred.
     * @throws GeneralSecurityException if an error ocurred.
     * @throws IllegalArgumentException if the configuration is invalid.
     */
    @Bean
    DirectoryApiClient client( final ApiCredentials creds ) 
            throws IOException, GeneralSecurityException, IllegalArgumentException {

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
    
}
