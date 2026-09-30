// KASOTI — settings (BUILD.md §2)
//
// Android is OPTIONAL at configure time. `:ui` and `:app-android` are only wired in
// when an SDK is discoverable, so `./gradlew :core:jvmTest` and `:eval:run` stay
// runnable on desktop-only machines and in CI. A full checkout with the SDK builds all.
//
// Layering (DESIGN.md §1):
//   :core      pure Kotlin, zero deps, zero expect/actual — takes ByteArray/FloatArray only
//   :platform  the ONLY module with expect/actual (crypto, imaging, ocr, inference)
//   :eval      JVM harness CLI over :core
//   :app-desktop  JVM console over :core + :platform

@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "kasoti"

include(":core")
include(":platform")
include(":eval")
include(":app-desktop")
// :ui is pure Kotlin presentation state (no Compose, no Android), so it is always built —
// gating it behind the SDK would mean it is never compiled on the machines that can check it.
include(":ui")

val androidSdk: String? = run {
    val local = file("local.properties").takeIf { f -> f.exists() }?.let { f ->
        java.util.Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }
    local?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
}

val androidEnabled = androidSdk?.let { file(it).isDirectory } == true

if (androidEnabled) {
    include(":app-android")
}

gradle.extra["kasoti.androidEnabled"] = androidEnabled
