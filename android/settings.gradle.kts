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
        // Admitted Central namespaces use one fixed mirror and keep their existing SHA-256 pins.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/")
            }
            filter {
                listOf(
                    "com.almworks.sqlite4java", "com.github.ben-manes.caffeine", "com.google.android",
                    "com.google.api.grpc", "com.google.auto", "com.google.auto.service", "com.google.auto.value",
                    "com.google.code.findbugs", "com.google.code.gson", "com.google.crypto.tink",
                    "com.google.dagger", "com.google.devtools.ksp", "com.google.errorprone",
                    "com.google.flatbuffers", "com.google.guava", "com.google.j2objc", "com.google.jimfs",
                    "com.google.protobuf", "com.google.testparameterinjector", "com.google.zxing",
                    "com.googlecode.juniversalchardet", "com.ibm.icu", "com.intellij", "com.squareup",
                    "com.squareup.okhttp3", "com.squareup.okio", "com.sun.activation", "com.sun.istack",
                    "com.sun.xml.bind", "com.sun.xml.bind.mvn", "com.sun.xml.fastinfoset", "commons-codec",
                    "commons-io", "commons-logging", "io.github.java-diff-utils", "io.grpc", "io.netty",
                    "io.opencensus", "io.opentelemetry", "io.perfmark", "jakarta.activation", "jakarta.xml.bind",
                    "javax.annotation", "javax.inject", "junit", "net.java", "net.java.dev.jna",
                    "net.sf.jopt-simple", "net.sf.kxml", "org.apache", "org.apache.commons",
                    "org.apache.httpcomponents", "org.bitbucket.b_c", "org.bouncycastle", "org.checkerframework",
                    "org.codehaus.groovy", "org.codehaus.mojo", "org.conscrypt", "org.eclipse.ee4j",
                    "org.glassfish.jaxb", "org.hamcrest", "org.jdom", "org.jetbrains", "org.jetbrains.kotlin",
                    "org.jetbrains.kotlin.android", "org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlinx",
                    "org.jspecify", "org.junit", "org.jvnet.staxex", "org.ow2", "org.ow2.asm", "org.robolectric",
                    "org.slf4j", "org.sonatype.oss", "org.tensorflow", "org.xerial", "org.yaml",
                ).forEach { includeGroup(it) }
            }
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
        // Admitted Central namespaces use one fixed mirror and keep their existing SHA-256 pins.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/")
            }
            filter {
                listOf(
                    "com.almworks.sqlite4java", "com.github.ben-manes.caffeine", "com.google.android",
                    "com.google.api.grpc", "com.google.auto", "com.google.auto.service", "com.google.auto.value",
                    "com.google.code.findbugs", "com.google.code.gson", "com.google.crypto.tink",
                    "com.google.dagger", "com.google.devtools.ksp", "com.google.errorprone",
                    "com.google.flatbuffers", "com.google.guava", "com.google.j2objc", "com.google.jimfs",
                    "com.google.protobuf", "com.google.testparameterinjector", "com.google.zxing",
                    "com.googlecode.juniversalchardet", "com.ibm.icu", "com.intellij", "com.squareup",
                    "com.squareup.okhttp3", "com.squareup.okio", "com.sun.activation", "com.sun.istack",
                    "com.sun.xml.bind", "com.sun.xml.bind.mvn", "com.sun.xml.fastinfoset", "commons-codec",
                    "commons-io", "commons-logging", "io.github.java-diff-utils", "io.grpc", "io.netty",
                    "io.opencensus", "io.opentelemetry", "io.perfmark", "jakarta.activation", "jakarta.xml.bind",
                    "javax.annotation", "javax.inject", "junit", "net.java", "net.java.dev.jna",
                    "net.sf.jopt-simple", "net.sf.kxml", "org.apache", "org.apache.commons",
                    "org.apache.httpcomponents", "org.bitbucket.b_c", "org.bouncycastle", "org.checkerframework",
                    "org.codehaus.groovy", "org.codehaus.mojo", "org.conscrypt", "org.eclipse.ee4j",
                    "org.glassfish.jaxb", "org.hamcrest", "org.jdom", "org.jetbrains", "org.jetbrains.kotlin",
                    "org.jetbrains.kotlin.android", "org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlinx",
                    "org.jspecify", "org.junit", "org.jvnet.staxex", "org.ow2", "org.ow2.asm", "org.robolectric",
                    "org.slf4j", "org.sonatype.oss", "org.tensorflow", "org.xerial", "org.yaml",
                ).forEach { includeGroup(it) }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "MeloXWatch"
include(":app", ":companion")
