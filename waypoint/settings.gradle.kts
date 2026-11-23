pluginManagement {
    repositories {
        google()
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

rootProject.name = "waypoint"

// Pure Kotlin: trip model, planner, runtime state machine (no Android, no network).
include(":trip")
// JVM: HTTP clients for Valhalla, Photon, TfL, Transitous and GBFS behind the :trip interfaces.
include(":providers")
// Android phone app + Android Auto.
include(":app")
