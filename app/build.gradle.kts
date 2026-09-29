/*
 * app — the Android shell: single activity, Compose navigation graph, Hilt
 * graph root, foreground services and the platform integrations (permissions,
 * notifications, WebShare asset hosting) that the pure modules cannot own.
 */
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.morsecode"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.morsecode"
        // API 23 (Android 6.0) is a product requirement, not a fallback.
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "app.morsecode.MorseTestRunner"
        vectorDrawables.useSupportLibrary = false

    }

    signingConfigs {
        // Release signing is configured from keystore.properties, which is
        // git-ignored. See doc/release.md — no secret is ever committed.
        create("release") {
            val props = rootProject.file("keystore.properties")
            if (props.exists()) {
                // `java` is shadowed by the project's java extension inside this
                // block, hence the explicit import at the top of the file.
                val keystoreProperties = Properties().apply { props.inputStream().use { load(it) } }
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only signed when keystore.properties is present; otherwise the
            // unsigned bundle is produced and doc/release.md explains signing.
            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }

    lint {
        abortOnError = true
        // CI parses the machine-readable reports and republishes the findings as
        // check-run annotations: the Actions log and artifact hosts are not
        // reachable from every environment that has to debug a red build.
        xmlReport = true
        textReport = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // NewApi / UnusedResources stay on: they are the automated API 23 gate.
        //
        // The three version checks are off on purpose. The toolchain is pinned
        // (AGP 8.13 · Gradle 8.13 · Hilt 2.58 · androidx.hilt 1.3.0, see
        // doc/decisions/ADR-0001-toolchain.md), so "a newer version is available"
        // is permanently true for 44 findings and would drown the checks that can
        // actually fail. MissingTranslation is off because the app ships exactly
        // one locale: the mockup's English copy.
        disable += setOf(
            "MissingTranslation",
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
    }

    dependenciesInfo {
        // No dependency metadata is embedded in release artifacts.
        includeInApk = false
        includeInBundle = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-design"))
    implementation(project(":core-data"))
    implementation(project(":core-storage"))
    implementation(project(":core-transfer"))
    implementation(project(":transport-lan"))
    implementation(project(":transport-nearby"))
    implementation(project(":webshare-server"))
    implementation(project(":media"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The Compose BOM only versions artifacts on the configurations it is added
    // to; the test source sets do not inherit it, and an unversioned
    // ui-test-junit4 fails dependency resolution (and with it lint's model task).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Robolectric Compose tests need the ComponentActivity this artifact declares,
    // and unit tests run for the release variant too — where debugImplementation
    // is not on the classpath.
    testImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
