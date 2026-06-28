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
        // The SensorBio SDK, consumed as a binary from the public Maven repo (GitHub Pages).
        // This is the ONLY SensorBio-specific line a customer adds.
        maven { url = uri("https://getsensr-io.github.io/mobile_sensorbio_sdk_android_binary/") }
    }
}
rootProject.name = "SensorBioExampleApp"
include(":app")
