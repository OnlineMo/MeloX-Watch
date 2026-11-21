import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
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
        getByName("main").java.srcDir("../shared-transfer/src/main/kotlin")
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
    bundle {
        language {
            // The user can change languages offline; both locales must remain in every package.
            enableSplit = false
        }
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
// 同一模块的两个 SDK 版本须独立解析，避免冲突选择只留下其中一份。
val robolectricSdk30 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdk")
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    from(robolectricSdk30)
    into(robolectricSdkDirectory)
}

// Android's loader accepts a verified host JNI path; JVM classes never enter the test classpath.
val bundledSqliteHost by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val bundledSqliteHostPlatform = run {
    val systemName = System.getProperty("os.name").lowercase(Locale.ROOT)
    val architecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val arm64 = architecture == "aarch64" || architecture == "arm64"
    val x64 = architecture == "amd64" || architecture == "x86_64"
    when {
        systemName.contains("linux") && x64 -> "linux_x64" to "libsqliteJni.so"
        systemName.contains("linux") && arm64 -> "linux_arm64" to "libsqliteJni.so"
        systemName.contains("mac") && x64 -> "osx_x64" to "libsqliteJni.dylib"
        systemName.contains("mac") && arm64 -> "osx_arm64" to "libsqliteJni.dylib"
        systemName.contains("windows") && x64 -> "windows_x64" to "sqliteJni.dll"
        else -> error("No pinned SQLite host native for $systemName/$architecture")
    }
}
val bundledSqliteHostDirectory = layout.buildDirectory.dir("sqlite-host")
val prepareBundledSqliteHost by tasks.registering(Sync::class) {
    from(provider { zipTree(bundledSqliteHost.singleFile) })
    include("natives/${bundledSqliteHostPlatform.first}/${bundledSqliteHostPlatform.second}")
    into(bundledSqliteHostDirectory)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    dependsOn(prepareBundledSqliteHost)
    val nativeCrashPhase = System.getenv("MELOX_CI_BUILD_PHASE")
        ?.takeIf { it.matches(Regex("[a-z][a-z0-9-]{0,80}")) } ?: "unmonitored"
    val nativeCrashDirectory = layout.buildDirectory.dir("ci-native-crash/$nativeCrashPhase")
    // Raw hs_err files stay outside automatic reports; the monitor exports only bounded stack summaries.
    // Core dumps can grow far beyond the test heap, so a fatal worker must not create one.
    jvmArgs("-XX:-CreateCoredumpOnCrash",
        "-XX:ErrorFile=${nativeCrashDirectory.get().asFile.absolutePath}/hs_err_pid%p.log")
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.usePreinstrumentedJars", "true")
    systemProperty("robolectric.dependency.dir", robolectricSdkDirectory.get().asFile.absolutePath)
    systemProperty("robolectric.enabledSdks", "26,30")
    systemProperty("androidx.sqlite.driver.bundled.path",
        bundledSqliteHostDirectory.get().dir("natives/${bundledSqliteHostPlatform.first}").asFile.absolutePath)
    systemProperty("androidx.sqlite.driver.bundled.name", bundledSqliteHostPlatform.second)
    maxParallelForks = 1
    // One Gradle task owns the workers; two test forks have an explicit combined 1 GiB heap budget.
    maxHeapSize = "512m"
    doFirst {
        nativeCrashDirectory.get().asFile.mkdirs()
        maxParallelForks = 1
        val workerLimit = gradle.startParameter.maxWorkerCount
        val eligible = System.getenv("GITHUB_ACTIONS") == "true" &&
            System.getenv("CI_UNSIGNED_APK") == "true" && System.getenv("GITHUB_EVENT_NAME") == "pull_request"
        var probeStatus = if (eligible) "unavailable" else "not-requested"
        var resources: Map<*, *>? = null
        var interrupted: InterruptedException? = null
        var process: Process? = null
        if (eligible) {
            try {
                process = ProcessBuilder("python3", rootProject.file("../scripts/ci/probe_ci_resources.py").absolutePath,
                    "--worker-limit", workerLimit.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val ownedProbe = checkNotNull(process)
                if (!ownedProbe.waitFor(3, TimeUnit.SECONDS)) {
                    probeStatus = "timed-out"
                } else if (ownedProbe.exitValue() != 0) {
                    probeStatus = "failed"
                } else {
                    val bytes = ownedProbe.inputStream.use { it.readNBytes(4097) }
                    if (bytes.size > 4096) {
                        probeStatus = "oversized"
                    } else {
                        resources = JsonSlurper().parseText(String(bytes, Charsets.UTF_8)) as? Map<*, *>
                        probeStatus = if (resources == null) "invalid" else "parsed"
                    }
                }
            } catch (error: InterruptedException) {
                interrupted = error
            } catch (_: Exception) {
                probeStatus = "unavailable"
            } finally {
                // The helper never spawns children. Only its returned handle may be stopped and reaped.
                try {
                    process?.let { ownedProbe ->
                        if (ownedProbe.isAlive) ownedProbe.destroy()
                        val cleanupDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
                        while (ownedProbe.isAlive && System.nanoTime() < cleanupDeadline) {
                            try {
                                ownedProbe.waitFor(50, TimeUnit.MILLISECONDS)
                            } catch (error: InterruptedException) {
                                if (interrupted == null) interrupted = error else interrupted?.addSuppressed(error)
                            }
                            if (ownedProbe.isAlive) ownedProbe.destroyForcibly()
                        }
                        check(!ownedProbe.isAlive) { "Owned CI resource probe did not terminate" }
                        try {
                            ownedProbe.inputStream.close()
                        } finally {
                            try { ownedProbe.outputStream.close() } finally { ownedProbe.errorStream.close() }
                        }
                    }
                } finally {
                    if (interrupted != null) Thread.currentThread().interrupt()
                }
            }
        }
        interrupted?.let { error -> throw error }
        fun measuredInteger(key: String): Long? = when (val value = resources?.get(key)) {
            is Int -> value.toLong().takeIf { it >= 0L }
            is Long -> value.takeIf { it >= 0L }
            else -> null
        }
        fun measuredCpu(key: String): Double? = (resources?.get(key) as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 && it <= 262144.0 }
        val schemaMatches = measuredInteger("schema_version") == 1L &&
            measuredInteger("worker_limit") == workerLimit.toLong()
        val cpu = measuredCpu("effective_cpu_count")
        val total = measuredInteger("effective_memory_total_bytes")
        val available = measuredInteger("effective_memory_available_bytes")
        val complete = schemaMatches && resources?.get("measurement_complete") == true &&
            resources.get("cgroup_namespace_initial_verified") == true &&
            cpu != null && cpu > 0.0 && total != null && total > 0L &&
            available != null && available <= total
        // A single Test task does not run application compilation in parallel. Before forking,
        // 3 GiB available reserves 1 GiB for two 512 MiB heaps and 2 GiB for native/system growth.
        // Native and daemon PSS are not hard bounded; resource usage, OOM and elapsed time need cloud measurement.
        // Unknown cgroup/ancestor constraints keep one fork; this is a trial, not a speed guarantee.
        if (complete && workerLimit >= 2 && checkNotNull(cpu) >= 2.0 &&
            checkNotNull(total) >= 6L * 1024 * 1024 * 1024 &&
            checkNotNull(available) >= 3L * 1024 * 1024 * 1024 &&
            measuredInteger("max_parallel_forks") == 2L) {
            maxParallelForks = 2
        }
        if (probeStatus == "parsed") probeStatus = if (complete) "measured" else "incomplete"
        val report = linkedMapOf<String, Any?>(
            "schema_version" to 1, "task" to path, "worker_limit" to workerLimit,
            "probe_status" to probeStatus, "measurement_complete" to complete,
            "max_parallel_forks" to maxParallelForks, "test_heap_limit_bytes" to 512L * 1024 * 1024,
        )
        report["effective_cpu_count"] = measuredCpu("effective_cpu_count")
        report["cgroup_quota_cpu_count"] = measuredCpu("cgroup_quota_cpu_count")
        for (key in listOf("logical_cpu_count", "affinity_cpu_count",
            "cgroup_cpuset_cpu_count", "effective_memory_total_bytes",
            "effective_memory_available_bytes", "physical_memory_total_bytes", "physical_memory_available_bytes",
            "cgroup_memory_limit_bytes", "cgroup_memory_available_bytes")) report[key] = measuredInteger(key)
        val initialCgroupNamespace = resources?.get("cgroup_namespace_initial_verified")
        report["cgroup_namespace_initial_verified"] = if (initialCgroupNamespace is Boolean) initialCgroupNamespace else null
        val cgroupVersion = resources?.get("cgroup_version")
        report["cgroup_version"] = when (cgroupVersion) {
            "v1", "v2", "hybrid", "none", "unavailable" -> cgroupVersion
            else -> null
        }
        // Only fixed classifications and bounded counts leave the probe. An incomplete
        // measurement must retain its cause without exposing kernel paths or identifiers.
        report["measurement_status"] = when (val status = resources?.get("measurement_status")) {
            "ok", "worker_limit", "low_resources", "invalid_arguments", "unsupported_platform",
            "read_unavailable", "input_too_large", "invalid_measurement", "cgroup_conflict",
            "cgroup_mapping_unavailable", "hidden_cgroup_ancestors", "probe_timeout", "internal_error",
            "unknown" -> status
            else -> null
        }
        for (key in listOf("cgroup_cpu_quota_status", "cgroup_cpuset_status", "cgroup_memory_status")) {
            report[key] = when (val status = resources?.get(key)) {
                "finite", "unlimited", "none", "unknown" -> status
                else -> null
            }
        }
        for (key in listOf("cgroup_cpu_ancestor_count", "cgroup_cpuset_ancestor_count",
            "cgroup_memory_ancestor_count", "cgroup_memory_limit_sample_count")) {
            report[key] = measuredInteger(key)?.takeIf { it <= 32L }
        }
        logger.lifecycle("MELOX_CI_TEST_RESOURCES " + JsonOutput.toJson(report))
    }
    failFast = true
    val testTaskPath = path
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit

        override fun beforeTest(test: TestDescriptor) {
            logger.lifecycle("MELOX_CI_TEST " + JsonOutput.toJson(mapOf(
                "event" to "start", "task" to testTaskPath,
                "className" to (test.className ?: test.parent?.className ?: "UnknownTestClass"), "name" to test.name,
            )))
        }

        override fun afterTest(test: TestDescriptor, result: TestResult) {
            logger.lifecycle("MELOX_CI_TEST " + JsonOutput.toJson(mapOf(
                "event" to "end", "task" to testTaskPath,
                "className" to (test.className ?: test.parent?.className ?: "UnknownTestClass"), "name" to test.name,
                "result" to result.resultType.name,
            )))
        }
    })
    testLogging {
        // A cancelled cloud test can lack JUnit XML; retain the native loader's full cause in its log.
        exceptionFormat = TestExceptionFormat.FULL
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
    implementation(libs.androidx.exifinterface)
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
    implementation(libs.sqlite.bundled)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    ksp(libs.room.compiler)
    kspTest(libs.room.compiler)
    add(robolectricSdk.name, libs.robolectric.sdk)
    add(robolectricSdk30.name, libs.robolectric.sdk30)
    add(bundledSqliteHost.name, libs.sqlite.bundled.host)
}
