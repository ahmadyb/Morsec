import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

// Morsecode — root build script.
// Plugin versions are declared once here and applied per module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

/**
 * Fails the build when a source file carries a marker that the master prompt
 * forbids in a delivered milestone. Run with: ./gradlew checkMilestoneHygiene
 */
tasks.register("checkMilestoneHygiene") {
    group = "verification"
    description = "Rejects TODO/FIXME markers and stub bodies in delivered sources."
    val forbidden = listOf(
        "TODO(", "TODO:", "FIXME", "XXX", "HACK", "NotImplementedError",
        "coming soon", "not implemented yet",
    )
    val extensions = setOf("kt", "kts", "xml", "ts", "css")
    // Resolved while the build is configured: the configuration cache forbids
    // touching the Project object from doLast.
    val baseDir = layout.projectDirectory.asFile
    val sourceRoots = listOf(
        "app/src", "core-model/src", "core-design/src", "core-data/src",
        "core-storage/src", "core-transfer/src", "transport-lan/src",
        "transport-nearby/src", "webshare-server/src", "media/src",
    ).map { layout.projectDirectory.dir(it).asFile }
    doLast {
        val offenders = mutableListOf<String>()
        sourceRoots.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { f -> f.isFile && f.extension in extensions } }
            .forEach { f ->
                f.readLines().forEachIndexed { index, line ->
                    forbidden.firstOrNull { line.contains(it, ignoreCase = true) }?.let { marker ->
                        offenders += "${f.relativeTo(baseDir)}:${index + 1} contains \"$marker\""
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException("Milestone hygiene violations:\n  " + offenders.joinToString("\n  "))
        }
        logger.lifecycle(
            "Milestone hygiene OK — no forbidden markers in ${sourceRoots.size} source roots.",
        )
    }
}


// --- Milestone 4 Part B item D: the security dependency lock, enforced in the task graph -------
//
// WHY THIS LIVES IN THE TASK GRAPH AND NOT ONLY IN A WORKFLOW STEP
// The committed digests in gradle/secure-artifact-checksums.txt and the lock in
// gradle/security-dependency-lock.json used to be checked by CI steps in a particular order.
// Step order is not enforcement: `./gradlew :transport-lan:compileDebugKotlin` invoked directly,
// or locally, compiled the crypto artifacts without any check at all. Making verification a
// prerequisite of the tasks that consume the artifacts means there is no invocation that reaches a
// compiler, test runner, lint analysis, dexer or packager first.
//
// WHAT IS AND IS NOT PROVED
// Proved here: the exact bytes on the compile, runtime, unit-test, instrumentation and packaging
// classpaths match the locked size and SHA-256, and no unapproved artifact resolves from a crypto
// group. Not proved: publisher metadata. Gradle keeps POM and module metadata in its own binary
// store, so a metadata change that resolves this same graph and these same bytes is not caught.
// This is security-scoped verification, not Gradle's global strict dependency verification.

/**
 * Recomputes SHA-256 over the crypto artifacts this project actually resolved and compares them to
 * the committed lock. Configuration-cache safe: the lock path, the resolved file collection and the
 * report path are all captured as task inputs and outputs, so the action never touches Project.
 */
abstract class VerifySecureDependencyLock : DefaultTask() {
    @get:InputFile
    abstract val lockFile: RegularFileProperty

    @get:InputFiles
    abstract val artifacts: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parse(lockFile.get().asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val components = parsed["components"] as? List<Map<String, Any?>>
            ?: throw GradleException("the security dependency lock has no components array")

        val resolved = artifacts.files.filter { it.isFile }
        val byName = resolved.associateBy { it.name }
        val problems = mutableListOf<String>()

        for (component in components) {
            val fileName = component["fileName"] as? String
                ?: throw GradleException("a locked component has no fileName")
            val expectedSize = (component["sizeBytes"] as? Number)?.toLong()
                ?: throw GradleException("locked component $fileName has no sizeBytes")
            val expectedSha = component["sha256"] as? String
                ?: throw GradleException("locked component $fileName has no sha256")

            val file = byName[fileName]
            if (file == null) {
                problems += "$fileName was not resolved on any verified configuration"
                continue
            }
            if (file.length() != expectedSize) {
                problems += "$fileName is ${file.length()} bytes but the lock records $expectedSize"
                continue
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expectedSha) {
                problems += "$fileName sha256 $actual does not match the locked $expectedSha"
            }
        }

        // A substituted module keeps its group in the module cache path, so an unapproved file under
        // a crypto group is caught even though its name is not in the lock.
        val lockedNames = components.mapNotNull { it["fileName"] as? String }.toSet()
        for (file in resolved) {
            val path = file.absolutePath.replace('\\', '/')
            val cryptoGroup = path.contains("/org.conscrypt/") || path.contains("/org.bouncycastle/")
            if (cryptoGroup && file.name !in lockedNames) {
                problems += "${file.name} resolved from a crypto group but has no lock entry"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "security dependency lock verification failed:\n  " + problems.joinToString("\n  "),
            )
        }
        reportFile.get().asFile.let { report ->
            report.parentFile.mkdirs()
            report.writeText(
                "verified ${components.size} locked crypto artifacts against ${resolved.size} resolved files\n",
            )
        }
        logger.lifecycle(
            "verified ${components.size} locked crypto artifacts over ${resolved.size} resolved files",
        )
    }
}

/** Task-name coverage for the AGP tasks that have no stable public type to match on. */
val protectedTaskPrefixes = listOf("lint", "dex", "package", "assemble", "bundle", "connected")

/** Only the projects that consume or package the crypto artifacts need the gate. */
val secureProjects = setOf("transport-lan", "app")

val secureConfigurationsByProject = mapOf(
    "transport-lan" to listOf(
        "debugCompileClasspath",
        "debugRuntimeClasspath",
        "releaseCompileClasspath",
        "releaseRuntimeClasspath",
        "debugUnitTestRuntimeClasspath",
        "releaseUnitTestRuntimeClasspath",
        "debugAndroidTestRuntimeClasspath",
    ),
    "app" to listOf(
        "debugRuntimeClasspath",
        "debugAndroidTestRuntimeClasspath",
    ),
)

val securityLockFile: RegularFile = layout.projectDirectory.file("gradle/security-dependency-lock.json")

subprojects {
    if (name !in secureProjects) return@subprojects
    val configurationNames = secureConfigurationsByProject.getValue(name)

    val verifySecureDependencyLock = tasks.register<VerifySecureDependencyLock>(
        "verifySecureDependencyLock",
    ) {
        group = "verification"
        description = "Verifies the locked crypto dependency graph before anything consumes it."
        lockFile.set(securityLockFile)
        reportFile.set(layout.buildDirectory.file("security/dependency-lock-report.txt"))
        // Captured while configuring. The configuration cache forbids touching Project from a task
        // action, and a FileCollection captured here is a supported cache input.
        artifacts.from(configurationNames.mapNotNull { configurations.findByName(it) })
    }

    // Typed matching where Gradle exposes a stable type, name prefixes where AGP does not.
    tasks.matching { task ->
        task is org.jetbrains.kotlin.gradle.tasks.KotlinCompile ||
            task is JavaCompile ||
            task is org.gradle.api.tasks.testing.Test ||
            protectedTaskPrefixes.any { task.name.startsWith(it) }
    }.configureEach {
        dependsOn(verifySecureDependencyLock)
    }
}
