package dev.sympho.google_group_resolver;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application bootstrap class.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@SuppressWarnings( "HideUtilityClassConstructor" ) // SpringBoot needs to instantiate it
public class Application {

    /** Creates a new instance. */
    public Application() {}

    /**
     * Program entrypoint.
     *
     * @param args Command line arguments.
     */
    @SuppressWarnings( "required.method.not.called" ) // registerShutdownHook deals with close()
    public static void main( final String[] args ) {

        new SpringApplicationBuilder( Application.class )
                .web( WebApplicationType.REACTIVE )
                .run( args )
                .registerShutdownHook();

    }

}
