plugins {
	java
    checkstyle
	id("org.springframework.boot") version "3.3.1"
}

group = "dev.sympho"
version = "0.0.1-SNAPSHOT"

val strictMode = findProperty("strict") == "true"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
    // Regular dependencies
    implementation(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webflux")

	runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Test dependencies
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Annotation processing
    annotationProcessor(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)) // https://github.com/gradle/gradle/issues/12519

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Dev tools
    developmentOnly(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)) // https://github.com/gradle/gradle/issues/12519

    developmentOnly("org.springframework.boot:spring-boot-devtools")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

if (strictMode) {
    println("Using strict mode")
    checkstyle {
        maxWarnings = 0
    }
}
