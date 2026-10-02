/*
 * core-transfer — transfer engine contracts, framing, checksums and the resume
 * state machine. Pure JVM (java.io / java.nio.ByteBuffer only) so the exact same
 * code paths that run on an API 23 phone can be exercised by JVM tests, and so
 * nothing in this module can accidentally reach for an unsupported platform API.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    // explicitApi() is intentionally off: see ADR-0001.
}

/*
 * The dependency list is deliberately this short.
 *
 * This module is the part of the engine that has to behave identically on an
 * API 23 phone and in a JVM test, and it is the part a hostile peer talks to
 * first, so it compiles against the JDK alone: java.nio.ByteBuffer,
 * java.nio.charset and java.security.MessageDigest are the complete set of
 * non-Kotlin types its main sources import. The reducer, codec and checksums
 * are all synchronous and value-returning, so there is no coroutine here to
 * schedule and nothing to inject — a frame in, a decision out.
 *
 * kotlinx-serialization is absent on purpose: the wire format is a hand-rolled
 * byte layout (see TransferSnapshotCodec, and the codec package) because the
 * frame header has to be a fixed 44 bytes with a CRC32 over it, which no
 * general-purpose serializer will produce.
 *
 * tools/verify/transfer-limits.mjs fails if an import creeps in that is not on
 * the allow-list below, so this list cannot silently grow.
 */
dependencies {
    api(project(":core-model"))

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
