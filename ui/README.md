# KASOTI — :ui  (DESIGN.md §1, D7 · AGENTS.md §2)

Shared presentation state for the field app and the Post-Console. **No Compose, no Android, no
platform dependency of any kind** — a plain `kotlin-jvm` module over `:core`.

---

## 1. The decision, and why

DESIGN.md D7 chose Compose Multiplatform for team leverage, and the long-term shape is a KMP
module with `androidTarget()` + `jvm()` and `@Composable` screens shared between the phone and
the console. **That shape is not taken here.** Three reasons, none of them taste:

1. **A Compose Multiplatform module in `:ui` could not be verified here.** There is no Android
   SDK, so `androidTarget()` cannot be configured and a `@Composable` screen would be entirely
   unverified code — not one annotation, not one plugin resolution, not one artifact coordinate.
   The reason the *whole module* is not SDK-gated is precisely that it does not use Compose:
   `:ui` is plain `kotlin-jvm` and is always built, whereas `:app-android` at least has a real
   `build.gradle.kts` somebody can point a device at.
2. **The plugin resolves at configuration time for the whole build.** An unresolvable Compose
   artifact would break `./gradlew :core:jvmTest` for a teammate with no interest in UI work —
   a much worse outcome than a thin, separately-addable binding layer.
3. **The logic is what needs testing, and tests are what credibility rests on.** Every decision
   that can be wrong — which step is next, whether a failed quality gate blocks, what a finding
   is called in Hindi, whether a GREY renders as an accusation — lives here as plain functions
   and plain data, and is tested on a bare JVM.

**The cost, stated plainly:** the Compose binding is a *separate* piece of work. `:app-android`
ships one (`view/ComposeFieldView.kt`); a desktop agent writes a second. Both are leaf code that
cannot contain a verdict decision, because the decision lives in `VerdictPresenter`.

**Migration** (one mechanical PR, no logic change): `kotlin.multiplatform` + `androidTarget()` +
`jvm()`, add the `compose-multiplatform` and `compose-compiler` plugin aliases, wrap the
`ScreenState` renders in `@Composable`, delete the two `FieldView` implementations. **No file
under `dev.kasoti.ui` changes its logic.**

---

## 2. What is in here

| File | What it owns | Tested by |
|---|---|---|
| `FieldStrings.kt` | UI chrome in English + Hindi. Deliberately *not* the finding vocabulary. | `FieldStringsTest` (7) |
| `VerdictVisuals.kt` | `VerdictTone` (the verdict → visual-feeling policy), `AccusationLexicon` | `AccusationLexiconTest` (4) |
| `VerdictPresenter.kt` | `VerdictReport` → `VerdictScreen`. **The only place a `FindingCode` becomes a string.** | `VerdictPresenterTest` (9) |
| `CaptureStep.kt` | The four step slots, the live quality meter, the stepper, and the ≤4-step cap | `CaptureStepTest` (13) |
| `ScreenState.kt` | The permission state machine, the one screen's state, the trust card, the palette roles | `FlowControllerTest` (18) |
| `FlowController.kt` | The reducer: `(AppState, UiEvent) -> AppState`. The only writer. | `FlowControllerTest` |
| `FieldView.kt` | The renderer contract, plus a recording no-op view for tests | used by all |
| `QualityMeterFactory.kt` | The `:core` ⇄ `:ui` quality conversion, both directions | `app-android`'s `QualityBridgeTest` (6) |

### Why there are two string catalogues

`dev.kasoti.i18n.Messages` is keyed by `FindingCode`, lives in `:core`, and is asserted complete
against the whole enum by `:core`'s own test. `FieldStrings` is the app's own furniture —
"Retake photo", "Step 2 of 4" — and belongs with the module that renders it.

The split matters most because of what must **not** be duplicated: a finding row's text is
always `Messages.of(code, language)`, never a `FieldStrings` key and never `Finding.message`
(which is a log string in English and is not translatable). `VerdictPresenter` is the only place
that resolves a code, and there is no way to configure it to do otherwise.
`FieldStringsTest` asserts the two catalogues share no strings, so a copy-paste cannot start a
drift.

---

## 3. The three properties worth a reader's attention

**A failed quality gate blocks the advance, structurally (FR-C1).** `CaptureProgress.blocker`
and `FlowController.advance` mean a `UiEvent.AdvanceStep` on a blocked step is a *no-op that
leaves the state byte-identical* — so a double-tap cannot slip past, and the operator gets the
instruction from the meter that is already on screen rather than from a second mechanism that
could disagree with it.

**GREY cannot look like an accusation.** Three layers, in increasing order of paranoia:

1. `VerdictTone.RETAKE` is the only non-clear tone with `isAccusatory = false` that is also not
   `SECONDARY`, and `isNeutralSurface = true` is a compile-time fact about the enum.
2. `VerdictPresenter.checkedAsNonAccusatory` scans the *assembled* GREY screen through
   `AccusationLexicon` and throws on a hit. `Messages` is in `:core` and its Hindi strings were
   written by other people; a future edit that made a GREY cause read like a verdict ("document
   is invalid") would be caught here, not at a counter.
3. `AccusationLexicon` is in the *shared* module rather than inside one renderer, so a second
   renderer — a desktop one, a screenshot-diff test — is held to the same rule. The list is
   curated rather than generated, and `CaptureStepTest` documents why: "lying" is a substring of
   "underlying", so a short word needs a word-boundary rule before it can be added.

The retake *causes* also move out of the findings list into their own `retakeInstructions`
list, so a GREY screen shows "Image is blurry / Too much glare" under a RETAKE headline with no
findings table at all — because there are no findings.

**A verdict cannot be shown without a camera.** `FlowController.withPermission` sends any state
to `ScreenState.Permissions` when the permission is withdrawn, so a RED cannot remain on screen
after a revocation mid-screening. Tested.

---

## 4. Running the tests

```bash
# :ui is ALWAYS in the build. It is plain kotlin-jvm — no Android, no Compose, no SDK
# needed — so this works identically with or without an Android SDK present:
./gradlew :ui:test          # 44 tests

# app-android/tools/verify-offline.sh also compiles and tests this module, as part of
# its tier-1 pass. That is a second, independent path, not the only path.
./app-android/tools/verify-offline.sh
```

`settings.gradle.kts` does **not** gate `:ui` on the SDK. It always `include(":ui")`, and the
only SDK-conditional `include` in that file is `:app-android`. Earlier revisions of this README
said the opposite — that "without an SDK this module is not in the build at all", and that the
offline harness was the only way to test it — and that was wrong. The SDK gate once wrapped
both modules; it now wraps only `:app-android`. If you are reading a claim that `:ui` needs an
SDK, it is stale: CI runs `./gradlew :ui:test` unconditionally, with no SDK-detection guard
(`.github/workflows/ci.yml`, step `:ui:test — shared presentation state (not SDK-gated)`).

## 5. Unverified

Everything in this module is compiled and tested (44 tests, green). What has **not** been
verified is the *binding*: no `FieldView` implementation here has been compiled, because both
candidate renderers live in modules that need the SDK. The interface is small enough to implement
in an afternoon, which is the point of having made it an interface.
