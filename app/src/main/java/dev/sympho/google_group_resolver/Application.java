package dev.sympho.google_group_resolver;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class Application {

    @SuppressWarnings( "required.method.not.called" ) // registerShutdownHook deals with close()
    public static void main( String[] args ) {

        new SpringApplicationBuilder( Application.class )
                .web( WebApplicationType.REACTIVE )
                .run( args )
                .registerShutdownHook();

    }

}
