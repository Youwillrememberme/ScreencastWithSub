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
        // Aliyun still caches ffmpeg-kit (removed from Maven Central); also faster in-region
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "SubCast"
include(":app")
