// KASOTI — :platform (DESIGN.md §1, §3)
//
// The ONLY module allowed to contain expect/actual or platform I/O. Everything here is a
// thin adapter: decode bytes, hand them to JCA, return primitives. All judgement (thresholds,
// verdicts, feature maths) stays in :core so it can be tested without a device.
//
// Dependency policy:
//   - `api(project(":core"))` because the whole point of this module is to satisfy
//     `:core`'s `dev.kasoti.crypto.Primitives` interfaces, so consumers need those types.
//   - Tess4J is compile-scoped but *optional at runtime*: nothing on the verdict path
//     requires libtesseract to be present (BUILD.md §5, D5). See `OcrEngines` for the
//     manual fallback that keeps the system working without it.
//   - The TFLite runtime (ADR-3) is confined to `dev.kasoti.platform.ml.tflite`. It is
//     `implementation`, never `api`, so the interpreter never leaks out of this module.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * The only desktop classifiers `ai.djl.tflite:tflite-native-cpu` has ever published, for both
 * of its versions (2.4.1 and 2.6.2). Probed directly against Maven Central on 2026-09-30:
 * `windows-x86_64`, `osx-aarch64` and `osx-arm64` all return HTTP 404.
 *
 * Listed here rather than commented in the dependency block because the build *branches* on
 * membership: a checkout on an unlisted platform still configures, compiles and tests, and the
 * face layer reports `UNAVAILABLE` — which is the fail-closed behaviour BUILD.md §4 asks for.
 * A build that cannot resolve its dependencies is a worse failure than a build that knows it
 * has no inference runtime.
 */
val PUBLISHED_TFLITE_NATIVE_CLASSIFIERS = setOf("linux-x86_64", "osx-x86_64")

/** The host OS family. Read from system properties so this file needs no Gradle-internal import. */
val hostOsName: String = System.getProperty("os.name", "").lowercase()
val hostArch: String = System.getProperty("os.arch", "").lowercase()

kotlin {
    jvmToolchain(17)

    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }

    // Mirrors the `:core` gate so a desktop-only machine and CI never need the Android SDK.
    if (gradle.extra["kasoti.androidEnabled"] == true) {
        androidTarget {
            compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                api(project(":core"))

                // Desktop OCR (BUILD.md §5). Optional at runtime: `Tess4JocrEngine`
                // degrades to `available = false` and callers fall back to `ManualOcrEngine`.
                implementation(libs.tess4j)

                // ADR-3 — desktop TFLite runtime (AGENTS.md §5 five-line ADR; spike 01 §4).
                // what:     `ai.djl.tflite` — the genuine `org.tensorflow.lite.*` Java API
                //           (`tflite-engine`) plus desktop JNI (`tflite-native-cpu`).
                // why:      There is NO first-party TFLite JVM artifact. `org.tensorflow:
                //           tensorflow-lite:2.17.0` on Maven Central is a 1,411-byte RELOCATION
                //           stub to `com.google.ai.edge.litert:litert:1.0.1`, which is
                //           `<packaging>aar</packaging>`; 2.16.1 and earlier are AAR-only with
                //           no `.jar` at all. Android `.so` files link Bionic and cannot be
                //           loaded by a desktop JVM, so the AAR natives are not a substitute.
                //           DESIGN D1 (TFLite, same bytes, one hash) is therefore only reachable
                //           via this repackage. Full citations: docs/spikes/01-face-model.md §4.
                // size:     engine ~30 KB; native 3.4 MB (linux-x86_64) / 7.1 MB (osx-x86_64).
                //           Counted against the *desktop* distribution, not the APK.
                // license:  Apache-2.0 (both). No model weights ship in either jar.
                // alternative: ONNX Runtime JVM (`com.microsoft.onnxruntime:onnxruntime`, Apache-2.0)
                //           has first-class win/linux/osx natives and would fix the two missing
                //           platforms, but it needs the model in ONNX, not TFLite — which breaks
                //           D1's "same bytes on both platforms" and needs its own spike. NOT
                //           taken here. Rejected for now, documented rather than hidden.
                //
                // `isTransitive = false`: `tflite-engine` declares a dependency on
                // `ai.djl:api:0.27.0`, but `jdeps` shows the `org.tensorflow.lite.*` classes we
                // actually call reference no `ai.djl` class at all (the dependency flows one
                // way, `ai.djl.tflite.engine.*` -> `org.tensorflow.lite.*`). Verified by running
                // real inference with `ai.djl:api` absent from the classpath. Keeping it would add
                // megabytes of unused code to the distribution.
                (implementation(libs.djl.tflite.engine.get()) as ExternalModuleDependency).apply {
                    isTransitive = false
                }

                // The native classifier has to live here: version catalogs cannot express one.
                // Upstream publishes ONLY these two, for both available versions (2.4.1, 2.6.2):
                //   linux-x86_64  -> native/lib/libtensorflowlite_jni.so
                //   osx-x86_64    -> native/lib/libtensorflowlite_jni.dylib
                // There is NO windows-x86_64 and NO osx-aarch64 artifact (both HTTP 404). Those
                // platforms therefore report "review-only, no on-device inference", which is
                // exactly the tripwire response BUILD.md §4 prescribes. Selecting per-OS keeps a
                // Windows or Apple-Silicon checkout configuring and testing cleanly rather than
                // failing dependency resolution outright — the face layer degrades to UNAVAILABLE.
                val tfliteNativeClassifier = when {
                    hostOsName.startsWith("windows") -> "windows-x86_64"
                    hostOsName.startsWith("mac") && hostArch == "aarch64" -> "osx-aarch64"
                    hostOsName.startsWith("mac") -> "osx-x86_64"
                    else -> "linux-x86_64"
                }
                if (tfliteNativeClassifier in PUBLISHED_TFLITE_NATIVE_CLASSIFIERS) {
                    val nativeDep = libs.djl.tflite.native.get()
                    implementation(
                        "${nativeDep.module}:${nativeDep.versionConstraint.requiredVersion}:$tfliteNativeClassifier",
                    )
                }
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
