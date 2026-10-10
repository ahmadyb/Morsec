/*
 * transport-lan — bounded UDP multicast discovery (:33457) plus the selected-peer
 * TCP control handshake (:33456). The discovery lease owns and releases its
 * multicast lock, sockets, network callback and bounded workers. Part A has no
 * file-data channel.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.morsecode.transport.lan"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        // CI parses the machine-readable reports and republishes the findings as
        // check-run annotations: the Actions log and artifact hosts are not
        // reachable from every environment that has to debug a red build.
        xmlReport = true
        textReport = true
        warningsAsErrors = false
        // The toolchain is pinned, so "a newer version is available" can never be
        // acted on here — see doc/decisions/ADR-0001-toolchain.md.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            // Bouncy Castle publishes identical license resources in several jars;
            // notices and complete licenses are retained in app assets.
            excludes += setOf("/META-INF/LICENSE*")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-model"))
    api(project(":core-transfer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

/**
 * Downloads the debug runtime classpath artifacts without compiling anything.
 *
 * This exists because of ordering. Dependency byte verification has to happen before the first
 * task that *uses* the artifacts; previously the JVM unit tests compiled against Conscrypt and
 * Bouncy Castle first and the bytes were only checked afterwards, so a substituted JAR was
 * already inside the test run by the time it was reported. The `dependencies` task resolves
 * metadata but does not fetch artifact bytes, so it cannot serve this purpose.
 *
 * The file collection is handed to the action through `inputs.files` rather than through a
 * captured variable. Gradle serializes task actions, and neither a script-local val nor the
 * enclosing script object survives that: both were observed arriving null at execution time in
 * runs 38042110197 and 38042959500. `inputs.files` is task state, so it is retained, and reading
 * it inside the action keeps this working if the configuration cache is ever enabled.
 */
tasks.register("downloadDebugRuntimeArtifacts") {
    group = "verification"
    description = "Downloads debug runtime artifacts so their bytes can be verified before use."
    inputs.files(
        objects.fileCollection().from(
            provider { configurations.getByName("debugRuntimeClasspath") },
        ),
    )
    doLast {
        var count = 0
        inputs.files.forEach { file -> if (file.isFile) count += 1 }
        println("resolved $count debug runtime artifacts for byte verification")
    }
}
