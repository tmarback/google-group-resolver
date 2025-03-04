import org.cyclonedx.model.AttachmentText
import org.cyclonedx.model.License
import org.cyclonedx.model.OrganizationalContact
import org.cyclonedx.model.license.Expression

plugins {
	id("java-conventions")
    alias(libs.plugins.springboot)
    alias(libs.plugins.cyclonedx)
}

dependencies {
    // Regular dependencies
    implementation(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("org.slf4j:slf4j-api")

    implementation(libs.google.oauth.client)
    implementation(libs.google.api.directory)

    implementation(libs.apache.commons.collections)

    implementation(libs.resilience4j.ratelimiter)

    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("io.projectreactor:reactor-core-micrometer")

	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly(libs.otel.logs.autoconfigure)

    // Test fixture
    testFixturesImplementation(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("io.projectreactor:reactor-core")

    // Integration tests
    integrationTestImplementation("io.micrometer:micrometer-core")
    integrationTestCompileOnly("jakarta.validation:jakarta.validation-api") // Fixes validation annotations not being found

    // Annotation processing
    annotationProcessor(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)) // https://github.com/gradle/gradle/issues/12519

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // To deal with missing annotation warnings
    annotationProcessor(libs.spotbugs)
    annotationProcessor(libs.bnd.annotation)
    annotationProcessor(libs.osgi.annotation)

    // Dev tools
    developmentOnly(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)) // https://github.com/gradle/gradle/issues/12519

    developmentOnly("org.springframework.boot:spring-boot-devtools")
}

configurations {
    all {
        exclude(group = "commons-logging", module = "commons-logging")
    }
}

testing {
    suites { 
        val test by getting(JvmTestSuite::class) { 
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("io.projectreactor:reactor-test")
                implementation("io.projectreactor.addons:reactor-extra")
            }
        }

        val integrationTest by getting(JvmTestSuite::class) { 
            dependencies {
                implementation(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-starter-web")
            }
        }
    }
}

tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    this.archiveFileName.set("${rootProject.name}.${archiveExtension.get()}")
}

tasks.cyclonedxBom {
    setComponentName("google-group-resolver")
    setProjectType("application")
    setOutputName("sbom-jar.cdx")
    setOutputFormat("json")
    setSkipConfigs(listOf("checkerFrameworkAnnotatedJDK"))

    // Set contact information
    var contact = OrganizationalContact()

    contact.setName("Thiago Marback")
    contact.setEmail("tmarback@sympho.dev")

    setOrganizationalEntity { oe ->
        oe.addContact(contact)
    }

    // Set license information
    setLicenseChoice{ lc ->
        lc.setExpression(Expression("MIT"))
    }
}
