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

val verifySecureDependencyGovernance = tasks.register("verifySecureDependencyGovernance") {
    group = "verification"
    description = "Checks the exact Part B crypto runtime artifacts and pinned transitive versions."
    actions.add(org.gradle.api.Action<org.gradle.api.Task> { task ->
        val taskProject = task.project
        val expectedConscryptVersion = "2.7.0"
        val expectedBouncyCastleVersion = "1.86"
        val runtimeConfiguration = taskProject.configurations.findByName("debugRuntimeClasspath")
            ?: throw org.gradle.api.GradleException(
                "debugRuntimeClasspath is unavailable in ${taskProject.path}; " +
                    "configurations=${taskProject.configurations.names.sorted()}"
            )
        val resolved = runtimeConfiguration.resolvedConfiguration.resolvedArtifacts
        val secureArtifacts = resolved.associate { artifact ->
            val id = artifact.moduleVersion.id
            "${id.group}:${id.name}" to id.version
        }.filterKeys { it.startsWith("org.conscrypt:") || it.startsWith("org.bouncycastle:") }

        check(secureArtifacts["org.conscrypt:conscrypt-android"] == expectedConscryptVersion) {
            "Conscrypt runtime artifact is missing or not pinned to $expectedConscryptVersion"
        }
        check(secureArtifacts["org.bouncycastle:bcpkix-jdk18on"] == expectedBouncyCastleVersion) {
            "Bouncy Castle PKIX runtime artifact is missing or not pinned to $expectedBouncyCastleVersion"
        }
        val expectedBouncyCastleArtifacts = setOf(
            "org.bouncycastle:bcpkix-jdk18on",
            "org.bouncycastle:bcutil-jdk18on",
            "org.bouncycastle:bcprov-jdk18on",
        )
        val resolvedBouncyCastleArtifacts = secureArtifacts.keys.filter { it.startsWith("org.bouncycastle:") }.toSet()
        check(resolvedBouncyCastleArtifacts == expectedBouncyCastleArtifacts) {
            "Unexpected Bouncy Castle runtime graph: $resolvedBouncyCastleArtifacts"
        }
        check(resolvedBouncyCastleArtifacts.all { secureArtifacts[it] == expectedBouncyCastleVersion }) {
            "All Bouncy Castle runtime artifacts must use version $expectedBouncyCastleVersion"
        }
        val licenseDirectory = taskProject.file("../app/src/main/assets/third_party_licenses")
        val apacheLicense = licenseDirectory.resolve("Apache-2.0.txt")
        val nettyLicense = licenseDirectory.resolve("licenses/LICENSE.netty.txt")
        val harmonyLicense = licenseDirectory.resolve("licenses/LICENSE.harmony.txt")
        check(
            apacheLicense.isFile &&
                nettyLicense.isFile &&
                harmonyLicense.isFile &&
                licenseDirectory.resolve("Conscrypt-NOTICE.txt").isFile &&
                licenseDirectory.resolve("BouncyCastle-LICENSE.txt").isFile
        ) { "Pinned crypto artifact notices/licenses are missing from app assets" }
        check(nettyLicense.readBytes().contentEquals(apacheLicense.readBytes()) &&
            harmonyLicense.readBytes().contentEquals(apacheLicense.readBytes())
        ) { "Conscrypt's Netty and Harmony Apache 2.0 license references must resolve exactly" }
        taskProject.logger.lifecycle("Secure crypto runtime graph verified: $secureArtifacts")
    })
}

tasks.named("check") {
    dependsOn(verifySecureDependencyGovernance)
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
