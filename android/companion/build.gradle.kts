import groovy.json.JsonOutput
import java.util.Properties
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val toolchain = Properties().apply {
    rootProject.file("../.ci/toolchain.properties").inputStream().use(::load)
}
val releaseVersion = Properties().apply {
    rootProject.file("../version.properties").inputStream().use(::load)
}

android {
    namespace = "io.github.onlinemo.meloxwatch.companion"
    compileSdk = toolchain.getProperty("android.compileSdk").toInt()
    buildToolsVersion = toolchain.getProperty("android.buildTools")
    defaultConfig {
        applicationId = "io.github.onlinemo.meloxwatch.companion"
        minSdk = toolchain.getProperty("android.minSdk").toInt()
        targetSdk = toolchain.getProperty("android.targetSdk").toInt()
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
    }
    flavorDimensions += "platform"
    productFlavors {
        create("phoneAndroid") { dimension = "platform" }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            isDebuggable = true
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("diagnostic") {
            initWith(getByName("release"))
            applicationIdSuffix = ".diagnostic"
            matchingFallbacks += "release"
            signingConfig = null
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(toolchain.getProperty("java.major"))
        targetCompatibility = JavaVersion.toVersion(toolchain.getProperty("java.major"))
    }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        // Compile the same actual wire/TLS source in each package; neither app depends on the other.
        getByName("main").java.srcDir("../shared-transfer/src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
    }
    testOptions { unitTests.isReturnDefaultValues = false }
}

androidComponents {
    finalizeDsl {
        if (System.getenv("CI_UNSIGNED_APK") == "true") {
            it.defaultConfig.signingConfig = null
            it.productFlavors.forEach { flavor -> flavor.signingConfig = null }
            it.buildTypes.forEach { buildType -> buildType.signingConfig = null }
        }
    }
}

kotlin {
    jvmToolchain(toolchain.getProperty("java.major").toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(toolchain.getProperty("java.major")))
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "512m"
    failFast = true
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
    val taskIdentity = path
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
        override fun beforeTest(test: TestDescriptor) {
            logger.lifecycle("MELOX_CI_TEST " + JsonOutput.toJson(mapOf(
                "event" to "start", "task" to taskIdentity,
                "className" to (test.className ?: test.parent?.className ?: "UnknownTestClass"), "name" to test.name,
            )))
        }
        override fun afterTest(test: TestDescriptor, result: TestResult) {
            logger.lifecycle("MELOX_CI_TEST " + JsonOutput.toJson(mapOf(
                "event" to "end", "task" to taskIdentity,
                "className" to (test.className ?: test.parent?.className ?: "UnknownTestClass"),
                "name" to test.name, "result" to result.resultType.name,
            )))
        }
    })
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
