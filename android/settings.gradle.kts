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

rootProject.name = "YuldashAndroid"
include(":app")


// External, permissionless UI observer; never packaged in the application.
if (System.getenv("YULDASH_TAXI_DRAFT_DEVICE_QA") == "true") {
    include(":taxiDraftObserver")
}
