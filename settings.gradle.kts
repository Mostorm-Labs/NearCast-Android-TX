pluginManagement {
    repositories {
        // Use Google and MavenCentral first for stability in CI (GitHub Actions)
        google()
        mavenCentral()
        gradlePluginPortal()
        
        // Fallback to Aliyun mirrors for local development speed in China
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = uri(rootDir.resolve("build/local-maven")))
        
        // Priority for official repos to ensure stability on CI
        google()
        mavenCentral()
        
        // Aliyun mirrors as secondary fallback
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "NearCast-Android-TX"
include(":app")
