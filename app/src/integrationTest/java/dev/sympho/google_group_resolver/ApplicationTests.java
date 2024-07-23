package dev.sympho.google_group_resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import dev.sympho.google_group_resolver.google.DirectoryApi;
import dev.sympho.google_group_resolver.google.DirectoryApiFixture;
import dev.sympho.google_group_resolver.google.ServiceConfiguration;
import dev.sympho.google_group_resolver.google.ServiceSettings;

/**
 * Tests for the application.
 */
@WebFluxTest
@Import( { ResolverConfiguration.class, ServiceConfiguration.class } )
@DirtiesContext // Cache state is shared so need to restart between tests
@Execution( ExecutionMode.SAME_THREAD ) // Needed because of @DirtiesContext
class ApplicationTests {

    /** The test web client. */
    @Autowired
    WebTestClient webClient;

    /**
     * Verifies that the application context can load.
     */
    @Test
    void contextLoads() {}

    /**
     * Argument provider for {@link #resolve(String)}.
     *
     * @return The arguments.
     */
    private static Stream<String> resolve() {

        return DirectoryApiFixture.RESOLVED_GROUPS.keySet().stream();

    }

    /**
     * Tests resolving one entity.
     *
     * @param email The email to test.
     */
    @ParameterizedTest
    @MethodSource
    public void resolve( final String email ) {

        final var expected = DirectoryApiFixture.RESOLVED_GROUPS.get( email );

        webClient.get()
                .uri( builder -> builder.path( "/resolve" )
                        .queryParam( "email", email )
                        .build() 
                )
                .exchange()
                .expectStatus().isOk()
                .expectBody( ResolveController.Response.class )
                .value( response -> assertThat( response.groups() ) 
                        .containsExactlyInAnyOrderElementsOf( expected )
                );

    }

    /**
     * Tests resolving many entities multiple times.
     */
    @Test
    public void resolveRepeated() {

        for ( int i = 0; i < 10; i++ ) {
            for ( final var entry : DirectoryApiFixture.RESOLVED_GROUPS.entrySet() ) {

                final var email = entry.getKey();
                final var expected = entry.getValue();

                webClient.get()
                        .uri( builder -> builder.path( "/resolve" )
                                .queryParam( "email", email )
                                .build() 
                        )
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody( ResolveController.Response.class )
                        .value( response -> assertThat( response.groups() ) 
                                .containsExactlyInAnyOrderElementsOf( expected )
                        );

            }
        }

    }

    /**
     * Test configuration.
     */
    @TestConfiguration
    public static class Configuration {

        /**
         * Creates the API client.
         *
         * @return The API client.
         */
        @Bean
        DirectoryApi client() {

            return DirectoryApiFixture.apiClient();

        }

        /**
         * Creates the resolver settings.
         *
         * @return The settings.
         */
        @Bean 
        ResolverSettings resolverSettings() {

            return new ResolverSettings( true );

        }

        /**
         * Creates the cache settings.
         *
         * @return The settings.
         */
        @Bean
        CacheSettings cacheSettings() {

            return new CacheSettings(
                    false, 
                    1000, 
                    Duration.ofMinutes( 1 ), 
                    Duration.ofDays( 2 ), 
                    Duration.ofMinutes( 1 ),
                    new CacheSettings.Seeder( false, Duration.ZERO )
            );

        }

        /**
         * Creates the client settings.
         *
         * @return The settings.
         */
        @Bean
        ServiceSettings clientSettings() {

            return new ServiceSettings(
                    1000, 
                    Duration.ofMillis( 1 )
            );

        }
        
    }

}
