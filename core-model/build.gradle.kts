/*
 * core-model — pure Kotlin domain module.
 *
 * Kept off the Android classpath on purpose: transfer states, protocol data
 * objects, formatting and capability gates are platform independent, so they
 * can be unit tested on the JVM in milliseconds and reused by the WebShare
 * server module.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    // explicitApi() is intentionally off: see ADR-0001.
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.javax.inject)
    // Qualifiers are part of this module's public API (see model/di).

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
