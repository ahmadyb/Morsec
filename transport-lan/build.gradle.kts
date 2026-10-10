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

/**
 * Downloads the debug runtime classpath artifacts without compiling anything.
 *
 * This exists because of ordering, not because resolving is otherwise hard. Dependency byte
 * verification has to happen before the first task that *uses* the artifacts. In the previous CI
 * ordering the JVM unit tests compiled against Conscrypt and Bouncy Castle first and the bytes
 * were only checked afterwards, so a substituted JAR was already inside the test run by the time
 * it was noticed. The `dependencies` task resolves metadata but does not fetch artifact bytes, so
 * it cannot be used for this; something has to materialize the files.
 */
tasks.register("downloadDebugRuntimeArtifacts") {
    group = "verification"
    description = "Downloads debug runtime artifacts so their bytes can be verified before use."
    val runtimeClasspath = configurations.named("debugRuntimeClasspath")
    doLast {
        val resolved = runtimeClasspath.get().incoming.artifactView { lenient(false) }.files
        var count = 0
        resolved.forEach { file -> if (file.isFile) count += 1 }
        println("resolved $count debug runtime artifacts for byte verification")
    }
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
