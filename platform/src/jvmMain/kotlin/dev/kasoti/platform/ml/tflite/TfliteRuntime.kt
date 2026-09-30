package dev.kasoti.platform.ml.tflite

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Where the desktop TFLite runtime is, and whether this machine can load it.
 *
 * ## Why this class exists at all
 *
 * There is no first-party TFLite JVM artefact. `org.tensorflow:tensorflow-lite:2.17.0` on
 * Maven Central is a 1,411-byte *relocation stub* pointing at `com.google.ai.edge.litert:litert`,
 * which is `<packaging>aar</packaging>`; 2.16.1 and earlier are AAR-only with no `.jar` published
 * at all, and no `<os>` classifier exists for any version. The Android `.so` files inside the AAR
 * are not a substitute — they link Bionic, not glibc. So DESIGN D1's "TFLite everywhere, same
 * bytes, one hash" is only reachable on a desktop JVM through a repackage of the TFLite Java API
 * plus separately published desktop JNI libraries. Full citations and the rejected alternatives
 * are in `docs/spikes/01-face-model.md` §4.
 *
 * ## The trap this class is built around
 *
 * `org.tensorflow.lite.TensorFlowLite` loads its native library from a static initialiser that
 * **swallows the failure**. If the library is not on `java.library.path`, the subsequent
 * `init()` throws:
 *
 * ```
 * java.lang.UnsatisfiedLinkError: 'java.lang.String org.tensorflow.lite.TensorFlowLite.nativeRuntimeVersion()'
 * ```
 *
 * which reads like a JNI ABI mismatch and is not one — the symbol is present in the library; the
 * library was simply never loaded. The only reliable diagnosis is to load it yourself and check
 * [availability]. Anything that reaches for `Interpreter` without going through here produces an
 * error message that will cost somebody a day.
 */
object TfliteRuntime {

    /** Resource path the `tflite-native-cpu` jar puts the library at. */
    const val NATIVE_RESOURCE_DIR = "native/lib"

    private const val LINUX_LIB = "libtensorflowlite_jni.so"
    private const val MAC_LIB = "libtensorflowlite_jni.dylib"
    private const val WINDOWS_LIB = "tensorflowlite_jni.dll"

    /** Sentinel recorded when [load] has not succeeded, so the probe runs at most once. */
    private val UNLOADED = Availability(
        available = false,
        detail = "not loaded yet",
        libraryName = null,
        platformSupported = isPlatformPublished(),
    )

    @Volatile
    private var state: Availability = UNLOADED

    /**
     * The outcome of trying to load the native library.
     *
     * [platformSupported] is separated from [available] on purpose: "this OS has no published
     * TFLite native" and "the native is here but failed to load" are different facts with
     * different fixes, and collapsing them into one boolean is how a missing-platform problem
     * gets misdiagnosed as a corrupt install for a week.
     */
    data class Availability(
        val available: Boolean,
        val detail: String,
        val libraryName: String?,
        val platformSupported: Boolean,
    )

    /**
     * Load the native library if it has not been loaded already. Idempotent.
     *
     * Never throws. A missing runtime is an expected state on a Windows or Apple-Silicon
     * checkout, and the caller's correct response is to report `UNAVAILABLE`, not to crash a
     * screening session (BUILD.md §4: those OSes ship "review-only, no on-device inference").
     */
    @Synchronized
    fun load(): Availability {
        if (state !== UNLOADED) return state
        state = attemptLoad()
        return state
    }

    val availability: Availability get() = load()

    /** Reset the memoised probe. Test-only; production never needs to reload a native library. */
    internal fun resetForTests() {
        synchronized(this) { state = UNLOADED }
    }

    private fun attemptLoad(): Availability {
        val libName = libraryNameForThisPlatform()
            ?: return Availability(
                available = false,
                detail = "no published TFLite JNI native for ${describePlatform()}; " +
                    "review-only, no on-device inference (BUILD.md §4)",
                libraryName = null,
                platformSupported = false,
            )

        if (!isPlatformPublished()) {
            return Availability(
                available = false,
                detail = "no published TFLite JNI native for ${describePlatform()}; " +
                    "review-only, no on-device inference (BUILD.md §4)",
                libraryName = libName,
                platformSupported = false,
            )
        }

        // Already loaded by a sibling class in the same JVM (e.g. two interpreters on one file).
        // `System.load` would throw; a fresh load is not needed and is not wanted.
        if (nativeAlreadyLoaded()) {
            return Availability(true, "already loaded in this JVM", libName, platformSupported = true)
        }

        val resource = "$NATIVE_RESOURCE_DIR/$libName"
        val stream = TfliteRuntime::class.java.classLoader.getResourceAsStream(resource)
            ?: return Availability(
                available = false,
                detail = "$resource not on the classpath — the desktop TFLite native dependency " +
                    "is missing (ADR-3 in platform/build.gradle.kts)",
                libraryName = libName,
                platformSupported = true,
            )

        val extracted: Path = stream.use { input ->
            val dir = Files.createTempDirectory("kasoti-tflite")
            dir.toFile().deleteOnExit()
            val target = dir.resolve(libName)
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            target.toFile().deleteOnExit()
            target
        }

        return try {
            System.load(extracted.toAbsolutePath().toString())
            Availability(true, "loaded $libName from $resource", libName, platformSupported = true)
        } catch (e: UnsatisfiedLinkError) {
            Availability(
                available = false,
                detail = "System.load($libName) failed: ${e.message}",
                libraryName = libName,
                platformSupported = true,
            )
        } catch (e: SecurityException) {
            Availability(
                available = false,
                detail = "System.load($libName) denied by the security manager: ${e.message}",
                libraryName = libName,
                platformSupported = true,
            )
        }
    }

    /**
     * Whether this platform has *any* published native.
     *
     * Read from the same table the Gradle build branches on. They are deliberately duplicated
     * rather than shared: the build script is Kotlin DSL evaluated at configure time and cannot
     * see a compiled class, and a runtime check that disagrees with the dependency block would
     * be worse than either. Probed 2026-09-30: `windows-x86_64`, `osx-aarch64` and
     * `osx-arm64` are HTTP 404 for every published version of the artifact.
     */
    fun isPlatformPublished(): Boolean {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") && arch == "amd64" -> true
            os.contains("mac") || os.contains("darwin") -> arch == "x86_64"
            os.contains("windows") -> false
            else -> false
        }
    }

    private fun describePlatform(): String =
        "${System.getProperty("os.name")}/${System.getProperty("os.arch")}"

    private fun libraryNameForThisPlatform(): String? {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("windows") -> WINDOWS_LIB
            os.contains("mac") || os.contains("darwin") -> MAC_LIB
            os.contains("linux") -> LINUX_LIB
            else -> null
        }
    }

    private fun nativeAlreadyLoaded(): Boolean = try {
        // Touching the class runs its static initialiser, which attempts (and may swallow) a
        // loadLibrary. If that initialiser succeeded the library is present.
        org.tensorflow.lite.TensorFlowLite.runtimeVersion()
        true
    } catch (_: Throwable) {
        // Either the static initialiser failed, or the library loaded but the JNI methods are
        // unresolved. Distinguish by attempting a raw load in the caller.
        loadedLibraries().any { it.endsWith("_jni") || it.contains("tensorflowlite") }
    }

    private fun loadedLibraries(): List<String> = try {
        val field = Class.forName("java.lang.System").getDeclaredField("loadedLibraryNames")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as List<String>).toList()
    } catch (_: Throwable) {
        emptyList()
    }
}

/**
 * True when a TFLite interpreter can be constructed on this machine.
 *
 * The single boolean every caller should branch on. `false` means the face layer is
 * `UNAVAILABLE` — never "assume a match".
 */
fun tfliteAvailable(): Boolean = TfliteRuntime.availability.available

/** Delete a file the runtime extracted. Test helper; the JVM's `deleteOnExit` covers the rest. */
internal fun deleteQuietly(file: File): Boolean = file.exists() && file.delete()
