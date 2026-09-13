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
        maven {
            url = uri("https://download.linphone.org/maven_repository/")
        }
    }
}

rootProject.name = "EntryRecorder"
include(":app")