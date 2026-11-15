import java.util.Properties
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

val toolchain = Properties()
rootProject.file("../.ci/toolchain.properties").inputStream().use(toolchain::load)
val releaseVersion = Properties()
rootProject.file("../version.properties").inputStream().use(releaseVersion::load)

android {
    namespace = "io.github.onlinemo.meloxwatch"
    compileSdk = toolchain.getProperty("android.compileSdk").toInt()
    buildToolsVersion = toolchain.getProperty("android.buildTools")

    defaultConfig {
        applicationId = "io.github.onlinemo.meloxwatch"
        minSdk = toolchain.getProperty("android.minSdk").toInt()
        targetSdk = toolchain.getProperty("android.targetSdk").toInt()
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
    }

    flavorDimensions += "platform"
    productFlavors {
        create("watchAndroid") {
            dimension = "platform"
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(toolchain.getProperty("java.major"))
        targetCompatibility = JavaVersion.toVersion(toolchain.getProperty("java.major"))
    }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = false
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory(layout.projectDirectory.dir("schemas"))
}

// Robolectric 不自行下载运行时 SDK；同一精确制品先经 Gradle 锁和 SHA-256 校验。
val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdk")
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(robolectricSdkDirectory)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.usePreinstrumentedJars", "true")
    systemProperty("robolectric.dependency.dir", robolectricSdkDirectory.get().asFile.absolutePath)
    systemProperty("robolectric.enabledSdks", "26")
    maxParallelForks = 1
}

// 稳定证书只在独立 runner 使用；不让 AGP 默认 Debug 配置进入可信分支产物。
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

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // 直接消费已使用的Android runtime API，此声明不经过available-at转发。
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.room.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric.test)
    ksp(libs.room.compiler)
    add(robolectricSdk.name, libs.robolectric.sdk)
}
