package dev.sympho.google_group_resolver;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationPredicate;

/**
 * Monitoring system customizations.
 */
@Configuration
public class MetricsConfiguration {

    /** The logger. */
    private static final Logger LOG = LoggerFactory.getLogger( MetricsConfiguration.class );

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

    /**
     * Injects the configured meter registry into the scheduler registry.
     *
     * @param registry The registry to inject.
     * @return The injection manager.
     */
    @Bean
    Object injectSchedulerRegistry( final MeterRegistry registry ) {

        return new Object() {

            @PostConstruct
            void inject() {

                LOG.info( "Injecting scheduler registry" );
                Metrics.SCHEDULER_REGISTRY.add( registry );

            }

            @PreDestroy
            void remove() {

                LOG.info( "Disabling scheduler registry" );
                Metrics.SCHEDULER_REGISTRY.remove( registry );

            }

        };

    }

}
