plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // implementation("org.checkerframework:checkerframework-gradle-plugin:0.6.55")
    implementation(plugin(libs.plugins.checkerframework))
}

// Helper function that transforms a Gradle Plugin alias from a
// Version Catalog into a valid dependency notation for buildSrc
fun DependencyHandlerScope.plugin(plugin: Provider<PluginDependency>) = plugin.map { 
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" 
}
