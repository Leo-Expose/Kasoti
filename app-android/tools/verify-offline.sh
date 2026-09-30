#!/usr/bin/env bash
#
# KASOTI — offline verification for :ui and :app-android's platform-free layer.
#
# WHY THIS EXISTS
#
#   `settings.gradle.kts` gates `:ui` and `:app-android` on a discoverable Android SDK, so on a
#   machine with no SDK neither module can be configured and *nothing* in them can be compiled
#   by Gradle. That is correct for the repo (it keeps `:core:jvmTest` runnable everywhere) and
#   useless for anyone trying to check the work. This script closes the gap: it compiles the
#   SDK-free half of both modules with the *pinned* Kotlin compiler, against the *real* `:core`
#   classes, and runs their unit tests on a bare JVM.
#
# WHAT IT VERIFIES (and this is the whole list)
#
#   · every file under `app-android/.../field/**`  — compiles, and 110 unit tests pass
#   · every file under `app-android/.../ml/**`     — compiles (the face-detector decode, sigmoid,
#                                                    anchor grid, NMS and the model's input
#                                                    preparation: no `android.*`, no TFLite)
#   · `app-android/.../platform/JcaCrypto.kt`     — compiles (JCA is not an Android API)
#   · every file under `ui/src/**`                 — compiles, and 44 unit tests pass
#   · therefore: every `:core` API these modules call actually exists with the signature used
#   · therefore: the Kotlin is syntactically and structurally valid
#   · therefore: the unit tests are real tests that pass, not placeholders
#   · therefore: the Android face-detector decode is bit-identical to `:platform`'s, and the
#     committed `eval/fixtures/face/raw_outputs/` tensors decode to their recorded answer
#   · TIER 2 (weaker, see below): `platform/TfliteFace.kt` and `platform/JcaCrypto.kt` **compile**,
#     against minimal hand-written stubs of the four `android.*` / `org.tensorflow.*` types they
#     touch, with `:core` on the classpath as the real compiled classes
#
# WHAT IT DOES NOT VERIFY — read this before trusting a green run
#
#   · anything that imports `android.*`, `androidx.*`, `org.tensorflow.*` or `com.google.mlkit.*`
#     in the **pure** tier — that is what the banned-import guard below enforces
#   · TIER 2 is a syntax-and-name-resolution gate, not a build. It proves the Kotlin is valid and
#     that the names resolve; it does NOT prove our stub signatures match the real SDK or the real
#     TFLite AAR. Read `app-android/tools/stubs/org/tensorflow/lite/Interpreter.java` before
#     changing anything there.
#   · resource XML, the manifest, Gradle configuration, dependency resolution, R8, aapt2, Compose
#   · that the app runs, or produces the right verdict on a device
#
#   In short: it proves the *logic* compiles and is correct, and for the face detector it proves
#   the decode half of DESIGN.md §3's comparability law against the desktop implementation. It
#   proves nothing about runtime behaviour on a device. See app-android/README.md
#   §"Verification status".
#
# WHY TIER 2 EXISTS
#
#   This module was written on a machine with no Android SDK, and the cost of that was four
#   independent compile errors in one file — a SAM conversion that does not exist, a call with too
#   few arguments, a property assignment on a Java options object that silently set nothing, and a
#   `/*` inside a KDoc that Kotlin's lexer rejects. None of them is visible by reading the code and
#   all of them would have been found by the first `assembleDebug`. Tier 2 finds that class of
#   defect here, on every run, on any machine.
#
# USAGE
#
#   ./app-android/tools/verify-offline.sh              # compile + test
#   ./app-android/tools/verify-offline.sh --list      # show which files are in and out
#   ./app-android/tools/verify-offline.sh --tier1      # the pure tier only (faster)
#
# Requires: a JDK (17+), a Gradle dependency cache that already holds the pinned Kotlin compiler,
# and one completed `./gradlew :core:jvmTest :platform:jvmTest` (the face-detector parity tests
# load `:platform`'s compiled classes, which is the whole point of them). On a machine that has
# run `./gradlew :core:jvmTest :platform:jvmTest` at least once, everything is present. Nothing
# here reaches the network.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

KOTLIN_VERSION="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
COROUTINES_VERSION="$(sed -n 's/^coroutines = "\(.*\)"/\1/p' gradle/libs.versions.toml)"

# ---------------------------------------------------------------------------------------------
# Locate the jars. Both Gradle's module cache and its distribution lib/ directory are searched;
# the compiler-embeddable artifact is in the module cache, and its transitive needs (trove4j,
# org.jetbrains:annotations) come from the same place.
# ---------------------------------------------------------------------------------------------
M2="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

find_jar() {
  # $1 = group, $2 = artifact, $3 = version glob
  find "$M2/$1/$2" -name "$3" -type f 2>/dev/null | sort | tail -1
}

KOTLINC_JAR="$(find_jar org.jetbrains.kotlin "kotlin-compiler-embeddable" "kotlin-compiler-embeddable-${KOTLIN_VERSION}.jar")"
STDLIB_JAR="$(find_jar org.jetbrains.kotlin kotlin-stdlib "kotlin-stdlib-${KOTLIN_VERSION}.jar")"
KTEST_JAR="$(find_jar org.jetbrains.kotlin kotlin-test "kotlin-test-${KOTLIN_VERSION}.jar")"
KTEST_J5_JAR="$(find_jar org.jetbrains.kotlin kotlin-test-junit5 "kotlin-test-junit5-${KOTLIN_VERSION}.jar")"
COROUTINES_JAR="$(find_jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm "kotlinx-coroutines-core-jvm-${COROUTINES_VERSION}.jar")"
TROVE_JAR="$(find_jar org.jetbrains.intellij.deps trove4j "trove4j-*.jar")"
ANNOTATIONS_JAR="$(find_jar org.jetbrains annotations "annotations-*.jar")"
JUPITER_API_JAR="$(find_jar org.junit.jupiter junit-jupiter-api "junit-jupiter-api-*.jar")"
JUPITER_ENGINE_JAR="$(find_jar org.junit.jupiter junit-jupiter-engine "junit-jupiter-engine-*.jar")"
PLATFORM_COMMONS_JAR="$(find_jar org.junit.platform junit-platform-commons "junit-platform-commons-*.jar")"
PLATFORM_ENGINE_JAR="$(find_jar org.junit.platform junit-platform-engine "junit-platform-engine-*.jar")"
PLATFORM_LAUNCHER_JAR="$(find_jar org.junit.platform junit-platform-launcher "junit-platform-launcher-*.jar")"
OPENTEST_JAR="$(find_jar org.opentest4j opentest4j "opentest4j-*.jar")"
APIGUARDIAN_JAR="$(find_jar org.apiguardian apiguardian-api "apiguardian-api-*.jar")"
CORE_CLASSES="core/build/classes/kotlin/jvm/main"
# `:platform`'s compiled classes, so the face-detector parity tests can run the DESKTOP decode
# beside the Android one. `:platform` is a KMP module whose actuals live in `jvmMain`; an Android
# module cannot resolve that source set, which is why the decode is duplicated in
# `app-android/.../ml/**` and why the duplication needs a test. This is that test's whole
# dependency. Its TFLite-touching classes are on disk too, but nothing here references them, so
# the missing `ai.djl.tflite` jars on this classpath are not a problem.
PLATFORM_CLASSES="platform/build/classes/kotlin/jvm/main"

require() {
  if [ -z "$1" ] || [ ! -f "$1" ]; then
    echo "verify-offline: missing $2" >&2
    echo "" >&2
    echo "  This script needs the pinned Kotlin compiler and JUnit already in the Gradle cache." >&2
    echo "  Run ./gradlew :core:jvmTest once first, or point GRADLE_USER_HOME at a cache that" >&2
    echo "  has it. It never downloads anything itself." >&2
    exit 2
  fi
}

require "$KOTLINC_JAR"      "kotlin-compiler-embeddable ${KOTLIN_VERSION}"
require "$STDLIB_JAR"       "kotlin-stdlib ${KOTLIN_VERSION}"
require "$KTEST_JAR"        "kotlin-test ${KOTLIN_VERSION}"
require "$KTEST_J5_JAR"     "kotlin-test-junit5 ${KOTLIN_VERSION}"
require "$COROUTINES_JAR"   "kotlinx-coroutines-core-jvm ${COROUTINES_VERSION}"
require "$TROVE_JAR"        "trove4j"
require "$ANNOTATIONS_JAR"  "org.jetbrains:annotations"
require "$JUPITER_API_JAR"  "junit-jupiter-api"
require "$JUPITER_ENGINE_JAR" "junit-jupiter-engine"
require "$PLATFORM_COMMONS_JAR" "junit-platform-commons"
require "$PLATFORM_ENGINE_JAR"  "junit-platform-engine"
require "$PLATFORM_LAUNCHER_JAR" "junit-platform-launcher"
require "$OPENTEST_JAR"     "opentest4j"
require "$APIGUARDIAN_JAR"  "apiguardian-api"

if [ ! -d "$CORE_CLASSES" ]; then
  echo "verify-offline: $CORE_CLASSES not found. Run ./gradlew :core:jvmTest first." >&2
  exit 2
fi

if [ ! -d "$PLATFORM_CLASSES" ]; then
  echo "verify-offline: $PLATFORM_CLASSES not found. Run ./gradlew :platform:jvmTest first." >&2
  echo "" >&2
  echo "  The face-detector parity tests compare the Android decode against :platform's, so they" >&2
  echo "  need :platform's compiled classes. Without them they would silently stop being a check." >&2
  exit 2
fi

# ---------------------------------------------------------------------------------------------
# The file split. The IN set must import no Android, AndroidX, TFLite or ML Kit type; the guard
# below proves it rather than trusting the list.
#
# `ml/**` is in the set because the face-detector decode was extracted out of the TFLite binding
# precisely so that it could be compiled and tested here. That is the whole point: the binding
# cannot be built on a machine with no SDK, and a decode nobody can run is a decode nobody can
# check against the desktop one.
# ---------------------------------------------------------------------------------------------
FIELD_SRC="app-android/src/main/java/dev/kasoti/android/field"
ML_SRC="app-android/src/main/java/dev/kasoti/android/ml"
CRYPTO_SRC="app-android/src/main/java/dev/kasoti/android/platform/JcaCrypto.kt"
BINDING_SRC="app-android/src/main/java/dev/kasoti/android/platform"
STUB_SRC="app-android/tools/stubs"
UI_SRC="ui/src/main"
FIELD_TEST_SRC="app-android/src/test/java/dev/kasoti/android/field"
ML_TEST_SRC="app-android/src/test/java/dev/kasoti/android/ml"
UI_TEST_SRC="ui/src/test/kotlin"

if [ "${1:-}" = "--list" ]; then
  echo "COMPILED AND TESTED HERE:"
  find "$FIELD_SRC" -name '*.kt' | sort
  find "$ML_SRC" -name '*.kt' | sort
  echo "  $CRYPTO_SRC"
  find "$UI_SRC" -name '*.kt' | sort
  echo
  echo "TESTS RUN HERE:"
  find "$FIELD_TEST_SRC" "$ML_TEST_SRC" "$UI_TEST_SRC" -name '*.kt' | sort
  echo
  echo "TIER 2 (compiled against hand-written API stubs — a name-resolution check, not a build):"
  echo "  $BINDING_SRC/TfliteFace.kt"
  echo "  $BINDING_SRC/JcaCrypto.kt"
  echo "  $STUB_SRC/**/*.java"
  echo
  echo "NOT COMPILED HERE (needs the real Android SDK):"
  find app-android/src/main/java -name '*.kt' \
    ! -path "$FIELD_SRC/*" ! -path "$ML_SRC/*" ! -name 'JcaCrypto.kt' ! -name 'TfliteFace.kt' | sort
  # Everything under ui/src that is neither ui/src/main nor a test that already ran above. Without
  # the second exclusion this lists ui's own tests as "not compiled here", which is a false alarm
  # about a suite that just ran.
  find ui/src -name '*.kt' ! -path "$UI_SRC/*" ! -path "$UI_TEST_SRC/*" | sort
  exit 0
fi

# Refuse to run if a file in the "platform-free" set has picked up a platform import. This is the
# property the whole script rests on, and a silent violation would turn a real gap into a green
# tick: JcaCrypto.kt reaching for android.security would stop compiling, and the *reason* would be
# buried in a wall of unrelated unresolved references.
BANNED='^import (android|androidx|org\.tensorflow|com\.google\.mlkit|com\.google\.android)'
LEAKS="$(grep -rEn "$BANNED" "$FIELD_SRC" "$ML_SRC" "$UI_SRC" "$FIELD_TEST_SRC" "$ML_TEST_SRC" "$UI_TEST_SRC" "$CRYPTO_SRC" 2>/dev/null || true)"
if [ -n "$LEAKS" ]; then
  echo "verify-offline: a platform import appeared in the SDK-free set:" >&2
  echo "$LEAKS" >&2
  echo "" >&2
  echo "  That file must move to the platform set, or the offline check stops covering it." >&2
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# The SDK gate must be identical in both places.
#
# `settings.gradle.kts` decides whether to *include* `:ui` / `:app-android`; the module's own
# `build.gradle.kts` decides whether to *configure* itself. A mismatch is the worst possible
# arrangement for a first build: one module configuring and the other failing, or — worse and
# much quieter — Gradle reporting success while the app is absent from the build. This is a text
# check rather than a Gradle one because the failure it guards against is a build that cannot run
# on the machine you are trying to check it from.
# ---------------------------------------------------------------------------------------------
check_gate() {
  local file="$1"
  for signal in 'kasoti.androidEnabled' 'local.properties' 'sdk.dir' 'ANDROID_HOME' 'ANDROID_SDK_ROOT'; do
    if ! grep -q "$signal" "$file"; then
      echo "verify-offline: $file does not check '$signal'; the SDK gate has drifted apart." >&2
      echo "  settings.gradle.kts and the module build file must decide the same way." >&2
      exit 1
    fi
  done
}
check_gate settings.gradle.kts
check_gate app-android/build.gradle.kts

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

CP="$STDLIB_JAR:$COROUTINES_JAR:$CORE_CLASSES"
TESTCP="$CP:$KTEST_JAR:$KTEST_J5_JAR:$JUPITER_API_JAR:$JUPITER_ENGINE_JAR:$PLATFORM_COMMONS_JAR:$PLATFORM_ENGINE_JAR:$PLATFORM_LAUNCHER_JAR:$OPENTEST_JAR:$APIGUARDIAN_JAR"

kotlinc() {
  local out="$1"; shift
  java -Xmx2g -cp "$KOTLINC_JAR:$STDLIB_JAR:$COROUTINES_JAR:$TROVE_JAR:$ANNOTATIONS_JAR" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -nowarn -jvm-target 17 -no-reflect -no-stdlib "$@" -d "$out"
}

echo "==> compiling :ui (main)"
kotlinc "$OUT/ui" -cp "$CP" "$UI_SRC"

echo "==> compiling :app-android field layer + face-detector maths + JcaCrypto"
kotlinc "$OUT/field" -cp "$CP" "$FIELD_SRC" "$ML_SRC" "$CRYPTO_SRC"

echo "==> compiling tests"
kotlinc "$OUT/uitest" -cp "$CP:$OUT/ui:$KTEST_JAR:$KTEST_J5_JAR:$JUPITER_API_JAR" "$UI_TEST_SRC"
# The field tests reach into `:ui` for the quality bridge, exactly as the module does at runtime
# (`implementation(project(":ui"))`), so `:ui` is on the test compile classpath here too.
kotlinc "$OUT/fieldtest" -cp "$CP:$OUT/field:$OUT/ui:$KTEST_JAR:$KTEST_J5_JAR:$JUPITER_API_JAR" "$FIELD_TEST_SRC"
# The face-detector tests reach into `:platform` for the desktop decode they must agree with, which
# is the entire reason they exist. `:platform` is not a dependency of `:app-android` — an Android
# module cannot resolve a KMP `jvmMain` source set — so this is a deliberate, documented
# test-only edge, and it is what makes the duplicated decode checkable rather than hopeful.
kotlinc "$OUT/mltest" -cp "$CP:$OUT/field:$PLATFORM_CLASSES:$KTEST_JAR:$KTEST_J5_JAR:$JUPITER_API_JAR" "$ML_TEST_SRC"

# A tiny launcher, so the script does not depend on junit-platform-console-standalone being in
# the cache (it usually is not; the individual jars are).
cat > "$OUT/Runner.java" <<'JAVA'
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class Runner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage("dev.kasoti"))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);
        var summary = listener.getSummary();
        var out = new java.io.PrintWriter(System.out);
        summary.printTo(out);
        summary.printFailuresTo(out, 8);
        out.flush();
        System.exit(summary.getTotalFailureCount() > 0 ? 1 : 0);
    }
}
JAVA
# `javac` needs the classpath on the *source* path too for the static import to resolve.
javac -cp "$TESTCP" -d "$OUT/runner" "$OUT/Runner.java"

echo "==> running tests"
java -cp "$OUT/runner:$OUT/fieldtest:$OUT/mltest:$OUT/uitest:$OUT/field:$OUT/ui:$PLATFORM_CLASSES:$TESTCP" Runner

# ---------------------------------------------------------------------------------------------
# TIER 2 — the Android/TFLite-facing part of the module, compiled against stubs.
#
# A strictly weaker check than tier 1 and a strictly stronger one than "this file is not compiled".
# It is here because the alternative was four compile errors sitting in `TfliteFaceDetector`
# unnoticed, and because a face-detection binding nobody can compile is a face-detection binding
# nobody can review. See app-android/tools/stubs/org/tensorflow/lite/Interpreter.java for exactly
# what it does and does not establish.
# ---------------------------------------------------------------------------------------------
if [ "${1:-}" = "--tier1" ]; then
  echo
  echo "tier 2 skipped (--tier1). TfliteFace.kt and JcaCrypto.kt were NOT compiled."
  exit 0
fi

# Only the two platform files that need no Compose, no CameraX and no androidx. The rest of the
# module (`view/`, `capture/`, `KasotiApp`, `MainActivity`) needs a real SDK and is out of reach
# here; `--list` says so explicitly.
STUBBABLE="TfliteFace.kt JcaCrypto.kt"

echo
echo "==> tier 2: compiling the TFLite/Android-facing sources against stubs"
javac -nowarn -d "$OUT/stubs" $(find "$STUB_SRC" -name '*.java') 2>&1 | grep -v '^Note:' || true
if [ ! -d "$OUT/stubs/org/tensorflow/lite" ]; then
  echo "verify-offline: the API stubs did not compile; tier 2 is not a check if they are broken." >&2
  exit 1
fi

kotlinc "$OUT/binding" -cp "$CP:$OUT/stubs" \
  "$BINDING_SRC/TfliteFace.kt" "$BINDING_SRC/JcaCrypto.kt" "$ML_SRC" "$FIELD_SRC"

echo
echo "tier 2 OK — the above files compile. That is a syntax-and-name-resolution result only."
echo "          It is NOT a build: resource XML, aapt2, d8, R8, Compose, dependency"
echo "          resolution and the real SDK/TFLite signatures are all still unverified."
