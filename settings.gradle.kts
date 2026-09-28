@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Morsecode"

/*
 * Module map. Boundaries follow doc/architecture.md:
 *   app              – Android shell: activity, navigation, DI graph, services
 *   core-model       – pure Kotlin domain models, enums, formatting (JVM module)
 *   core-design      – mockup design tokens + reusable Compose components
 *   core-data        – Room persistence, DataStore settings, repositories
 *   core-storage     – MediaStore / SAF / legacy storage adapters, permissions
 *   core-transfer    – protocol framing, checksums, resume + queue engine (JVM)
 *   transport-lan    – UDP discovery, TCP control/data transport
 *   transport-nearby – Google Play services Nearby Connections transport
 *   webshare-server  – embedded HTTP/1.1 server + local JSON API (JVM)
 *   media            – Media3 playback, MediaSession, metadata helpers
 *   webshare-ui      – TypeScript/CSS browser client (npm project, not a Gradle
 *                      module; its production bundle is embedded into app assets)
 */
include(":app")
include(":core-model")
include(":core-design")
include(":core-data")
include(":core-storage")
include(":core-transfer")
include(":transport-lan")
include(":transport-nearby")
include(":webshare-server")
include(":media")
