plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Declared with `apply false` so the AGP types resolve for the `android {}` extension
    // below, without forcing the Android plugin (or an SDK) on a desktop-only build.
    alias(libs.plugins.android.library) apply false
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
        // AGP requires an explicit namespace for library modules. This is what the
        // `androidTarget()` above does not set for us.
        extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
            namespace = "dev.kasoti.core"
            compileSdk = 34
            defaultConfig { minSdk = 26 }
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
