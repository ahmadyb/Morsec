/*
 * core-design — the approved visual system.
 *
 * Every colour, radius, type scale, spacing step, motion curve and icon in this
 * module is derived from doc/morsecode_material3_mockup.html. The parity harness
 * (node tools/verify/token-parity.mjs) re-reads the mockup and fails on any token
 * drift, so the design system cannot silently diverge from the approved
 * reference; Android CI runs the Gradle build, lint and tests.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.morsecode.core.design"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
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
        // NewApi stays enabled: it is the automated gate that keeps API 23 devices
        // safe. The pure-JVM modules cannot be checked by lint, so their API floor
        // is an explicit allowlist instead — see
        // doc/decisions/ADR-0002-webshare-server.md and doc/qa/lint-and-warnings.md.
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-model"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material3.window.size)
    api(libs.androidx.compose.ui.tooling.preview)
    debugApi(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Explicit for the same reason as in :app — do not rely on inheriting the BOM.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Robolectric Compose tests need the ComponentActivity this artifact declares,
    // and unit tests run for the release variant too — where debugImplementation
    // is not on the classpath.
    testImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
