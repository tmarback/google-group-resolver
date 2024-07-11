plugins {
	java
    checkstyle
}

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

tasks.withType<Test> {
	useJUnitPlatform()
}

val style by extra { findProperty("style") == "true" }

tasks.withType<Checkstyle> {
    ignoreFailures = !style
}

val strictMode by extra { findProperty("strict") == "true" }

if (strictMode) {
    println("Using strict mode")
    
    checkstyle {
        maxWarnings = 0
    }
}
