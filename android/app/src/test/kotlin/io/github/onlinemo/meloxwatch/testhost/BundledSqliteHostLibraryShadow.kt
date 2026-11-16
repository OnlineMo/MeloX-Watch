package io.github.onlinemo.meloxwatch.testhost

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/** Host JNI paths belong to one ClassLoader, while each SDK/config has its own sandbox. */
@Implements(className = "androidx.sqlite.driver.bundled.NativeLibraryLoader", isInAndroidSdk = false)
class BundledSqliteHostLibraryShadow {
    @field:RealObject private lateinit var realLoader: Any

    @Implementation
    protected fun loadLibrary(name: String) {
        require(name == "sqliteJni")
        val sandboxLoader = javaClass.classLoader
        check(realLoader.javaClass.classLoader === sandboxLoader)
        check(Class.forName("androidx.sqlite.driver.bundled.BundledSQLiteDriverKt", false, sandboxLoader)
            .classLoader === sandboxLoader)
        // System.load is caller-sensitive; this shadow and the actual JNI methods share this loader.
        System.load(libraryCopy.toString())
    }

    companion object {
        // The class is acquired per sandbox. Keep its loaded file until the test worker exits.
        private val libraryCopy: Path by lazy { copyVerifiedHostLibrary() }

        private fun copyVerifiedHostLibrary(): Path {
            val directory = requireNotNull(System.getProperty("androidx.sqlite.driver.bundled.path"))
            val name = requireNotNull(System.getProperty("androidx.sqlite.driver.bundled.name"))
            require(name in setOf("libsqliteJni.so", "libsqliteJni.dylib", "sqliteJni.dll"))
            val original = Paths.get(directory, name)
            check(original.isAbsolute && Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS))
            val temporary = Files.createTempDirectory("melox-watch-sqlite-")
            temporary.toFile().deleteOnExit()
            val copy = temporary.resolve(name)
            try {
                Files.copy(original, copy)
                copy.toFile().deleteOnExit()
                check(Files.mismatch(original, copy) == -1L) { "The sandbox JNI copy must retain the verified bytes" }
                return copy
            } catch (error: Throwable) {
                try { Files.deleteIfExists(copy) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                try { Files.deleteIfExists(temporary) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}
