package dev.sympho.google_group_resolver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

/**
 * Monitoring system customizations.
 */
@Configuration
public class MetricsConfiguration {

    /** Creates a new instance. */
    public MetricsConfiguration() {}

    /**
     * Disables observations on actuator endpoints.
     *
     * @return The filter.
     */
    @Bean
    ObservationPredicate noActuatorServerObservations() {

        return ( name, context ) -> {

            if ( name.equals( "http.server.requests" )
                    && context instanceof ServerRequestObservationContext serverContext ) {
                return !serverContext.getCarrier().getURI().getPath().startsWith( "/actuator" );
            } else {
                return true;
            }

        };

    }

}
