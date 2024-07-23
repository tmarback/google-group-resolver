plugins {
	id("java-conventions")
    alias(libs.plugins.springboot)
}

group = "dev.sympho"
version = "0.1.0"

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

	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("ch.qos.logback:logback-classic")

    // Test fixture
    testFixturesImplementation(enforcedPlatform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("io.projectreactor:reactor-core")

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
