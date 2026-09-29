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
