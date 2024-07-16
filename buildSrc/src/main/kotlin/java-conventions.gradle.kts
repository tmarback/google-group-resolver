plugins {
	java
    `java-test-fixtures`
    checkstyle
    id("org.checkerframework")
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

testing {
    suites { 
        withType(JvmTestSuite::class).configureEach { 
            useJUnitJupiter() 

            targets {
                all {
                    testTask.configure { 
                        systemProperty("junit.jupiter.execution.parallel.enabled", true)
                        systemProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", true)
                    }
                }
            }
        }

        val test by getting(JvmTestSuite::class) { 
            // Default and common is enough
        }

        val integrationTest by registering(JvmTestSuite::class) { 
            testType.set(TestSuiteType.INTEGRATION_TEST)

            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
            }

            targets { 
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }
    }
}

tasks.named("check") { 
    dependsOn(testing.suites.named("integrationTest"))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:all,-processing,-requires-transitive-automatic,-requires-automatic")
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

    tasks.named<JavaCompile>("compileJava") {
        options.compilerArgs.add("-Werror")
    }
}

// Checker Framework configuration

val checker by extra { findProperty("checker") == "true" }
val checkerVersion by extra("3.45.0")

dependencies {
    compileOnly("org.checkerframework:checker-qual:$checkerVersion")
    testFixturesImplementation("org.checkerframework:checker-qual:$checkerVersion")
    implementation("org.checkerframework:checker-util:$checkerVersion")
    checkerFramework("org.checkerframework:checker:$checkerVersion")
}

testing {
    suites { 
        withType(JvmTestSuite::class).configureEach { 
            dependencies {
                compileOnly("org.checkerframework:checker-qual:$checkerVersion")
            }
        }
    }
}

val stubDir by extra { rootProject.layout.projectDirectory.dir("stubs") }

checkerFramework {
    checkers = listOf(
        "org.checkerframework.checker.nullness.NullnessChecker",
        "org.checkerframework.checker.optional.OptionalChecker",
        "org.checkerframework.checker.interning.InterningChecker",
        "org.checkerframework.checker.tainting.TaintingChecker",
        "org.checkerframework.checker.regex.RegexChecker",
        "org.checkerframework.checker.formatter.FormatterChecker",
        "org.checkerframework.checker.signedness.SignednessChecker",
        "org.checkerframework.common.initializedfields.InitializedFieldsChecker",
        "org.checkerframework.checker.resourceleak.ResourceLeakChecker",
    )
    extraJavacArgs = listOf(
        "-AreportEvalWarns",
        "-Astubs=${stubDir}",
        "-AstubWarnIfNotFoundIgnoresClasses",
        "-AshowPrefixInWarningMessages",
    )
    incrementalize = true
    excludeTests = true
    skipCheckerFramework = !checker
}

val preCompiledClasses by extra { layout.buildDirectory.dir("pre-compiled") }

// "Test" tacked on at the end of name due to
// https://github.com/kelloggm/checkerframework-gradle-plugin/issues/281
val preCompile = tasks.register<JavaCompile>("preCompileTest") {
    description = "Pre-compiles classes to use in static verification"

    classpath = sourceSets["main"].compileClasspath
    source = sourceSets["main"].java
    destinationDirectory = preCompiledClasses

    checkerFramework {
        skipCheckerFramework = true
    }
}

if (checker) {
    println("Checker Framework analysis is enabled")

    tasks.named<JavaCompile>("compileJava") {
        dependsOn(preCompile)
    }

    dependencies {
        checkerFramework(files(preCompiledClasses))
    }
}
