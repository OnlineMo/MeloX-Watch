pluginManagement {
    if (System.getenv("GITHUB_ACTIONS") != "true" || System.getenv("CI") != "true") {
        throw GradleException("Android builds run only in the Cloud build workflow.")
    }

    val toolchain = java.util.Properties()
    settingsDir.resolve("../.ci/toolchain.properties").inputStream().use(toolchain::load)
    if (gradle.gradleVersion != toolchain.getProperty("gradle.version")) {
        throw GradleException("Gradle does not match the repository toolchain lock.")
    }

    repositories {
        // The admitted Kotlin Android marker bytes are the Maven Central publication.
        google {
            content { excludeModule("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin") }
        }
        mavenCentral()
        gradlePluginPortal {
            content { excludeModule("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin") }
        }
    }
    plugins {
        id("com.android.application") version toolchain.getProperty("agp.version")
        id("org.jetbrains.kotlin.android") version toolchain.getProperty("kotlin.version")
        id("org.jetbrains.kotlin.plugin.compose") version toolchain.getProperty("kotlin.version")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MeloXWatch"
include(":app", ":companion")
