plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Declared with `apply false` so the AGP types resolve for the `android {}` extension
    // below, without forcing the Android plugin (or an SDK) on a desktop-only build.
    alias(libs.plugins.android.library) apply false
}

// `apply false` puts AGP on the classpath but does not apply it, so the `android {}`
// extension the block below configures did not actually exist on any machine that had an
// SDK. It was never noticed because `:core`'s Android target had never been compiled —
// the same class of bug as the `plugins {}` ordering fault in `:app-android`. Applying it
// only when the SDK gate is open keeps a desktop-only build and CI exactly as before.
if (gradle.extra["kasoti.androidEnabled"] == true) {
    apply(plugin = "com.android.library")
}

// AGP requires an explicit namespace for library modules, and `androidTarget()` does not set one.
//
// This block is at the TOP LEVEL on purpose. It used to sit inside `kotlin { }`, where the name
// `extensions` resolves to `KotlinMultiplatformExtension.extensions` — its own
// `DefaultConvention`, holding `[ext, sourceSets]` — not to `Project.extensions`. Configuring
// `"android"` there therefore failed with "Extension with name 'android' does not exist. Currently
// registered extension names: [ext, sourceSets]", even though a probe printed `android` among the
// *project's* registered extensions one line earlier. Only the project-level container holds the
// AGP extension.
if (gradle.extra["kasoti.androidEnabled"] == true) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "dev.kasoti.core"
        // Raised 34 -> 35 to match `:app-android`. `:app-android` compiles against the same
        // androidx artifacts (core-ktx 1.15.0 / activity-compose 1.10.1, both already pinned),
        // whose AAR metadata demands `minCompileSdk = 35`. These modules consume one another's
        // Android outputs, so a mismatched ceiling here surfaces as a confusing
        // "requires compileSdk 35" error pointing at `:core` rather than at the real cause.
        // `minSdk` is deliberately still 26 — see the note in `app-android/build.gradle.kts`.
        compileSdk = 35
        defaultConfig { minSdk = 26 }
    }
}

kotlin {
    jvmToolchain(17)

    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }

    // Android target is only wired when an SDK exists (settings.gradle.kts gate), so that
    // `./gradlew :core:jvmTest` works on desktop-only machines and in CI.
    if (gradle.extra["kasoti.androidEnabled"] == true) {
        androidTarget {
            compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
