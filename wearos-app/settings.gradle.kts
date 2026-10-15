pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WearOS-ANCS-Bridge"
include(":app")
include(":shared") // Health Data Bridge wire format, shared with the relay

// Optional phone app: Samsung Health (sleep stages, SpO2, blood pressure) → watch.
// Built only once the Samsung Health Data SDK AAR is in relay/libs/ (not redistributable).
if (file("relay/libs").listFiles()?.any { it.name.endsWith(".aar") } == true) {
    include(":relay")
} else {
    println("WearBridge: :relay skipped — no Samsung Health Data SDK .aar in ${file("relay/libs").absolutePath}")
}
