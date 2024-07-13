plugins {
	id("java-conventions")
    alias(libs.plugins.springboot)
}

group = "dev.sympho"
version = "0.0.1-SNAPSHOT"

dependencies {
    // Regular dependencies
    implementation(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation(libs.google.oauth.client)
    implementation(libs.google.api.directory)

	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("ch.qos.logback:logback-classic")

    // Test dependencies
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

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
