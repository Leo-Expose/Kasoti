// KASOTI — :ui (DESIGN.md §1, D7; AGENTS.md §2)
//
// WHAT THIS MODULE IS, precisely:
//
//   Pure-Kotlin presentation state holders + a documented view interface.
//   NO Compose dependency. NO Android dependency. NO platform dependency at all.
//
// WHY (the decision the task asked to be stated explicitly):
//
//   DESIGN.md D7 chose Compose Multiplatform for team leverage, and the "right" long-term
//   shape is a KMP module with `androidTarget()` + `jvm()` and `@Composable` screens shared
//   between the field app and the Post-Console. That shape is NOT taken here, for three
//   concrete reasons, not for taste:
//
//   1. It cannot be compiled in this environment. There is no Android SDK here, and
//      `settings.gradle.kts` gates BOTH `:ui` and `:app-android` on a discoverable SDK, so a
//      Compose Multiplatform module in `:ui` would be entirely unverified code: not one
//      `@Composable`, not one plugin resolution, not one artifact coordinate. `:app-android`
//      at least has a real `build.gradle.kts` somebody can point a device at.
//   2. The Compose Multiplatform *plugin* (org.jetbrains.compose 1.8.0) plus the Compose
//      compiler plugin is a real, unpinnable-here failure mode: plugin resolution happens at
//      configuration time for every module in the build, so an unresolvable Compose artifact
//      would break `./gradlew :core:jvmTest` for a teammate with no interest in UI work.
//      That is a much worse outcome than a thin, separately-addable binding layer.
//   3. The logic is what needs testing, and tests are what credibility here rests on. Every
//      decision that can be wrong — which step is next, whether a failed quality gate
//      blocks, what a finding is called in Hindi, whether GREY renders as an accusation —
//      lives in this module as plain functions and plain data. Those are testable on a bare
//      JVM with no SDK and no graphics stack, and they are tested (see src/test).
//
// The cost of this choice, stated plainly: the Compose binding is a *separate, later* piece
// of work. `:app-android` ships one (Jetpack Compose, since `androidx-activity-compose` is
// already pinned in gradle/libs.versions.toml) implementing [dev.kasoti.ui.FieldView]. A
// desktop agent wanting the same screens writes a second implementation of the same
// interface. Both are ~200 lines of leaf code that cannot contain a verdict decision.
//
// MIGRATION PATH (one PR, mechanical):
//   1. `plugins { alias(libs.plugins.kotlin.multiplatform) }` + `androidTarget()` + `jvm()`
//   2. add `composeMultiplatform` and `compose-compiler` plugin aliases
//   3. wrap the `ScreenState` renders in `@Composable` funs; delete the two `FieldView`
//      implementations. No file under `dev.kasoti.ui.state` changes.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `:core` is the only dependency. It is a common-source-set KMP module; from a JVM
    // module Gradle resolves its `jvm()` variant, exactly as `:app-desktop` already does.
    // This is deliberately NOT `:platform` — `:platform` exposes `jvmMain` only (its
    // actuals use java.awt / ImageIO), so an Android consumer could not resolve it. The
    // Android implementations of those seams live in `:app-android` instead; see that
    // module's README for why, and for where they are meant to end up (`:platform/androidMain`).
    implementation(project(":core"))

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
