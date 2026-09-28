/*
 * webshare-server — the embedded HTTP/1.1 server and local JSON API.
 *
 * Pure JVM module by design (see doc/decisions/ADR-0002-webshare-server.md):
 * it uses only java.net.ServerSocket, java.io streams and java.util.zip, every
 * one of which exists on Android since API 1. Building it as a JVM module lets
 * the whole server be integration tested with real sockets on the JVM, and it
 * makes the API 23 compatibility argument auditable instead of hopeful.
 * Android specific pieces (asset lookup, notifications, foreground service) are
 * injected through the AssetSource / ServerHostPorts SPIs implemented in :app.
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
