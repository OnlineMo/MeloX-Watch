import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    }
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
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
