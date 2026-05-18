pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = uri(rootDir.resolve("build/local-maven")))
        google()
        mavenCentral()
    }
}

rootProject.name = "ScreenCastDemo"
include(":app")
