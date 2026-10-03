// KASOTI — root build script (BUILD.md §2).
//
// Deliberately thin. The version catalog in `gradle/libs.versions.toml` holds every pin, and
// each module's `build.gradle.kts` owns its own wiring. This file does two things and no more:
// stamps the group/version onto every project, and applies the two static-analysis plugins
// from one place.
//
// WHY THE LINT PLUGINS ARE APPLIED HERE, AND NOT PER MODULE
//
//   `./gradlew ktlintCheck detekt` was advertised in AGENTS.md §1 for a long time and did not
//   exist: `ktlint` had a version entry with no plugin row, and `detekt` was declared at the
//   root with `apply false`, so no module ever created the task. A quality gate that is
//   documented but absent is worse than one that is honestly marked absent, because the deck
//   and the docs both end up describing a build nobody is running.
//
//   Applying both plugins from the root `subprojects {}` block is what makes the tasks real
//   without duplicating four lines into five module build scripts — and it is the reason a new
//   module is linted by default rather than by remembering to opt in. The list below is
//   explicit rather than "every subproject" because `:app-android` is only in the build when
//   an SDK is present (settings.gradle.kts) and a lint task that appears and disappears with
//   the SDK is a gate nobody can rely on.

plugins {
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    // The ktlint plugin reads `org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension` when it
    // configures itself, so the Kotlin plugin has to be on the root build's classpath even
    // though the root applies nothing. Declaring it here with `apply false` is what puts it
    // there, and it also clears the pre-existing "Kotlin Gradle plugin was loaded multiple
    // times in different subprojects" warning, which fires because every module declares the
    // plugin alias in its own `plugins {}` block. The module blocks are left as they are:
    // their aliases carry the same version, so the duplicate load is harmless, and rewriting
    // five build scripts to drop a working pin is not a change this one deserves.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    // Declared here, with `apply false`, purely to put the Kotlin Android plugin on the
    // buildscript classpath WITH A KNOWN VERSION. Without it, a module that applies the
    // Kotlin Android plugin hits "the plugin is already on the classpath with an unknown
    // version": `:core` pulls AGP in for its `androidTarget()`, and AGP drags the Kotlin
    // Android plugin along without a version, so any later versioned request is refused.
    // Nothing applies it at the root — `:app-android` does, once its SDK gate opens.
    alias(libs.plugins.kotlin.android) apply false
    // Same reason, one step further: AGP must also be on the ROOT buildscript classpath,
    // because `com.android.tools.build:gradle` is the artifact that actually contains
    // `com.android.build.gradle.api.BaseVariant`. Resolving AGP only inside `:app-android`
    // puts it in that project's plugin classloader, where the Kotlin Android plugin cannot
    // see it, and applying kotlin.android dies with
    // `NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`. Declaring it here
    // puts it where both plugins can reach it. Nothing applies it at the root.
    alias(libs.plugins.android.application) apply false
    // The same trap, one id over. `com.android.application` and `com.android.library` are two
    // plugin ids inside the *same* `com.android.tools.build:gradle` jar, so declaring the first
    // above already puts AGP on the root classpath. A versioned request for the second from any
    // subproject — which is what `core/build.gradle.kts:5` does — is then refused with "the
    // plugin is already on the classpath with an unknown version". Both ids have to be named
    // here for both to resolve. This is the `apply false` declaration only: `:core` decides
    // whether to *apply* it from the SDK gate, and nothing applies it at the root.
    alias(libs.plugins.android.library) apply false
}

allprojects {
    group = "dev.kasoti"
    version = "0.1.0-SNAPSHOT"
}

// ADR — org.jlleitschuh.gradle.ktlint:12.3.0 (version already pinned in the catalog; the plugin
//   row and the ktlint engine pin are what this change adds)
//   what:  Gradle plugin that runs the ktlint CLI/ruleset per source set, and contributes the
//          `ktlintCheck` / `ktlintFormat` tasks.
//   why:   AGENTS.md §1 has advertised `./gradlew ktlintCheck` as the project's style gate
//          since the first commit and no such task existed. ktlint rather than detekt-formatting
//          alone because the repo has no detekt config file, and a formatter with an autofix is
//          the only one of the two that can be turned on without a human reading every finding.
//   size:  ktlint 1.5.0 CLI + ruleset, ~12 MB in the Gradle cache; ~0 KB in any artefact. It is a
//          build-time-only tool, so it counts against nothing in the 35 MB APK budget.
//   lic:   Apache-2.0 (both the plugin and ktlint). No code from either is shipped.
//   alt:   `detekt-formatting`, already pinned in the catalog, gives the same rules inside detekt
//          so there is one tool instead of two. Rejected *for now* only because it would couple
//          the style gate to detekt's default rule set, which is red on day one (see the note on
//          `buildUponDefaultConfig` below). Reconsider once the detekt config lands.

// ADR — io.gitlab.arturbosch.detekt:1.23.8 (version and plugin row both already pinned at the
//   root with `apply false`; only the per-module application is new here)
//   what:  Gradle plugin that adds the `detekt` task — static analysis for correctness smells
//          (complexity, long methods, error handling, empty catch blocks), which a formatter
//          cannot catch.
//   why:   Same reason as ktlint: the root already declared it, so the pin and the version
//          budget were paid for and nothing was consuming them. `detekt` reports; it does not
//          rewrite, so it needs a considered decision per finding rather than an autofix.
//   size:  ~35 MB in the Gradle cache (compiler-embeddable + ruleset), build-time only, 0 KB in
//          any shipped artefact.
//   lic:   Apache-2.0. Not copied into the product.
//   alt:   `ktlint` alone, which is what runs by default. It catches formatting and nothing
//          semantic, so a swallowed exception or a 400-line file would pass. Keeping both is
//          what makes AGENTS.md §2's "keep :core files <400 lines" enforceable rather than
//          aspirational.

/** Modules that are linted. `:app-android` is excluded on purpose — see the comment above. */
val LINTED_MODULES = setOf(":core", ":platform", ":app-desktop", ":eval", ":ui")

// Read here, not inside `subprojects {}`: the version-catalog `libs` accessor is not available
// in that block, and hoisting it also makes the pin a single value applied to all five modules.
val ktlintEngineVersion: String = libs.versions.ktlintEngine.get()

subprojects {
    if (path !in LINTED_MODULES) return@subprojects

    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // The plugins default to `src/main/kotlin` + `src/test/kotlin`, which for `:core` and
    // `:platform` is empty: those are Kotlin Multiplatform modules and their code lives in
    // `src/commonMain/kotlin`, `src/commonTest/kotlin`, `src/jvmMain/kotlin` and
    // `src/jvmTest/kotlin`. Pointing both tools at the whole `src` tree means a module lints
    // the same way regardless of which target it happens to declare, which is the only way a
    // shared source set can be held to the same standard on desktop and on device.
    val kotlinSources = fileTree("src") { include("**/*.kt") }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension>("detekt") {
        source.setFrom(kotlinSources)
        // `buildUponDefaultConfig` = detekt's default rules PLUS a repo config file, not instead
        // of it. A `config/detekt/detekt.yml` that turns rules off would be a weakened gate
        // (AGENTS.md §5), so the default ruleset stays on and anything the repo disagrees with
        // has to be argued for in the PR, like every other exception.
        buildUponDefaultConfig = true
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension>("ktlint") {
        version.set(ktlintEngineVersion)
        // ktlint 1.x replaced the plugin's `codeStyle` flag with the `ktlint_code_style`
        // editorconfig property, so the style is set the way ktlint itself reads it. The
        // repository already follows `ktlint_official`: `kotlin.code.style = official` is in
        // gradle.properties and the tree is written to the official formatter's output. The
        // plugin's default (`intellij_idea`) disagrees with the repo on about forty rules and
        // would report the codebase as wrong for being consistent with itself.
        additionalEditorconfig.set(mapOf("ktlint_code_style" to "ktlint_official"))
        android.set(false)
        ignoreFailures.set(false)
    }

    // Both plugins make `check` depend on them by default. `./gradlew check` and `./gradlew
    // build` are green in this tree today and the two rulesets are not, so leaving the default
    // in place would turn every build red for a reason that has nothing to do with whatever is
    // being built — the fastest way to make a maintainer learn to skip a gate.
    //
    // The tasks stay real and runnable: `./gradlew ktlintCheck detekt` reports them, CI runs
    // both steps and prints the finding count, and nothing is switched off. What is deferred is
    // only the `check` -> lint edge, and it is deferred explicitly rather than by accident. The
    // TODO in AGENTS.md §1 names the milestone for re-attaching it, on the evidence that
    // clears the backlog — not on a date.
    afterEvaluate {
        val checkTask = tasks.findByName("check") ?: return@afterEvaluate
        val keep = checkTask.dependsOn.filterNot { dependency ->
            val name = (dependency as? TaskProvider<*>)?.name ?: return@filterNot false
            name == "detekt" || name.startsWith("ktlint")
        }
        checkTask.setDependsOn(keep)
    }
}

logger.lifecycle(
    "KASOTI: ktlintCheck and detekt are available on " +
        LINTED_MODULES.joinToString(", ") +
        ", and are deliberately NOT part of 'check' until their backlogs are cleared " +
        "(AGENTS.md §1). Run them explicitly; do not skip them.",
)
