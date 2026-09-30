// KASOTI — :app-desktop (DESIGN.md §1, BUILD.md §3)
//
// The Post-Console: secondary review, diary admin, sync hub, shift report, case-bundle
// export, override with reason codes.
//
// Headless is a hard requirement, not a preference. There is no Android SDK in CI, no
// display guarantee on a post machine, and a reviewer who cannot run the thing will not
// run the thing. So this pass ships the domain logic plus a fully functional text UI, and
// the view layer is kept behind interfaces so a Compose Multiplatform surface can be added
// without touching the logic.
//
// Compose Multiplatform is DELIBERATELY not a dependency in this pass. It is a ~1.4 MB
// plug-in plus a large native/artifact download, and adding it would put a third-party
// download on the critical path of a build that has to stay green on a machine with no
// display. Tracked in the module README.

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.kasoti.desktop.MainKt")
}

tasks.named<JavaExec>("run") {
    // The console's default model lookup is repository-relative
    // (`SvmModelFile.DEFAULT_PATH` = `eval/models/svm_print_v1.json`), so it needs the repository
    // root as the working directory or a provisioned console finds nothing and silently abstains
    // on every patch. `:eval` already sets this for the same reason; without it the two disagreed
    // about where the repository was, which is a class of bug that looks exactly like "the model
    // did not load".
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform"))

    // The sidecar-JSON reader and the case-bundle writer. Runtime only: the sidecar schema
    // is read through `Json.parseToJsonElement` rather than the compiler plugin, so there
    // is no codegen step and no plugin/codegen version coupling on the build's critical path.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
