// https://developer.android.com/build#settings-file
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudStream"
include(":app", ":shared", ":library", ":docs", ":desktopApp")
// KloudStream desktop (Windows/Linux), kept apart from upstream's desktopApp.
include(":kloudstream-desktop")
