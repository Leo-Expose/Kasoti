// KASOTI — `:eval`, the evaluation harness CLI (EVAL.md §3).
//
// Depends on `:core` only. It must never depend on `:platform`: the harness has to be
// runnable on a laptop and in CI with no device, and `:platform` carries the JCA crypto
// bindings. Where the harness needs a primitive `:core` leaves behind as an interface
// (`dev.kasoti.crypto.SignatureVerifier`), `:eval` supplies a clearly-labelled harness stub.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // The harness measures `:core`; it must not re-implement it.
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.kasoti.eval.MainKt")
    // EVAL.md §2 targets are 10k MRZ rows and thousands of macro patches; the default 512 MB
    // heap is not enough for the FFT working set in `full`.
    applicationDefaultJvmArgs = listOf("-Xmx3g", "-Xss4m")
}

tasks.named<JavaExec>("run") {
    // The harness must be runnable from the repo root regardless of the module dir, and a
    // non-zero exit has to reach the console *and* fail the build (EVAL.md: CI gates on it).
    workingDir = rootProject.projectDir
    isIgnoreExitValue = false
    // `./gradlew :eval:run --args="smoke"` appends to these, so nothing user-supplied is lost.
    jvmArgs("-Dfile.encoding=UTF-8", "-Duser.timezone=UTC")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
