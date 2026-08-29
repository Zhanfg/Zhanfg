pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://artifactory.appodeal.com/appodeal-public/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "MFGA-Xposed"
include(":app")
