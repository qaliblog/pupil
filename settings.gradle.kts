pluginManagement {
    repositories {
        // ✅ Official Google Maven repo — must be first for AndroidX/CameraX
        google()

        // 🌍 Optional: EU mirror of Maven Central (faster in France)
        maven { url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/") }

        // ✅ Gradle Plugin Portal for plugin dependencies
        gradlePluginPortal()

        // Optional fallback
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ✅ Google Maven repo — required for AndroidX (CameraX, AppCompat, etc.)
        google()

        // 🌍 Optional: EU mirror for Maven Central
        maven { url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/") }

        // Fallbacks
        mavenCentral()
    }
}

rootProject.name = "pupil"
include(":app")