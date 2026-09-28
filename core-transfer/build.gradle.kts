/*
 * core-transfer — transfer engine contracts, framing, checksums and the resume
 * state machine. Pure JVM (java.io / java.nio.ByteBuffer only) so the exact same
 * code paths that run on an API 23 phone can be exercised by JVM tests, and so
 * nothing in this module can accidentally reach for an unsupported platform API.
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
    api(project(":core-model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
