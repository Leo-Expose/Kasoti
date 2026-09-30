# KASOTI — Roadmap (ROADMAP.md) · Final development M0–M4 (+M5 sketch)

**Assumes 1 person, solo-maintained** (was written for 6 people × ~4 weeks; see §7). Effort in
person-days (pd). **The Owner column is decorative:** there is one maintainer, so every row is
the maintainer's, and the column is kept only to show which tradition a task came from
(`docs/HANDOFF.md` §2). Every task ends in its DoD or it isn't done — and no milestone has had
its DoD satisfied yet. `docs/STATUS.md` is the authority on what is actually done.

## M0 — Core + harness + clip + first numbers (Week 1)
Theme: truth before screens. No UI beyond debug screens.

| ID | Task | Owner | pd | DoD |
|---|---|---|---|---|
| M0.1 | Freeze `kasoti-sync/1` v1 + diary schema + finding codes + fusion-rules draft | Lead+Core | 2 | SYNC/FUSION merged; fixtures compile |
| M0.2 | `:core` mrz/checks/qr-parse/factory-math/face-math/diary-file/sync/audit | Core | 5 | unit+property tests green; coverage ≥80% touched |
| M0.3 | Eval harness CLI (`smoke`/`full`) + CI wiring + size/network-ban/scrubber checks | Vision+Core | 4 | `eval smoke` green in CI; metrics JSON emitted |
| M0.4 | Model spike (embed A/B/C + BlazeFace+NMS on JVM&Android; OCR size check) | Android+Vision | 4 | spike doc + DECISION (weights hashed, licenses ok) |
| M0.5 | Macro dataset v1 (≥600 patches, 3 lights, clip/no-clip) + calibration card v1 | Vision+all | 3 | DATA layout filled; collector agreement signed |
| M0.6 | SPECIMEN mock cards (4 tracks, watermarked) + print fakes (inkjet+laser+copy+screen) | Vision | 2 | physical set boxed + photographed |
| M0.7 | Clip v2 build ×3 (macro+polarizer+UV+shroud) + focal calibration note | Android+Hw | 2 | 3 clips work on 2 phones; photos in `hardware/` |
| M0.8 | MRZ 10k corpus + QR fixtures (test keys) + face pairs v1 (consented) | Vision | 3 | corpora in `eval/`; gates scripted |
| M0.9 | Train svm_print_v1 (sklearn, export JSON) + report held-out numbers | Vision | 2 | weights + model card; numbers in EVAL log |
| M0.10 | Milestone review: acceptance per SPEC §6 | all | 1 | Go/Cut/Replan recorded |

**M0 exit:** first real numbers exist (however bad) · sync file round-trips · clip exists · model decision locked. **Decision dates:** embed/detector **closed 2026-09-30** — detector adopted, embedder rejected on licence grounds (`docs/spikes/01-face-model.md`) · OCR route **undecided and undecidable** without a build.

**M0 status: not exited.** `:core` and the harness are done and green (620 tests across four
modules; `smoke` 11 pass/1 skip, `full` 13 pass/6 skip — run `eval-20260929-smoke-a1c7` and
`eval-20260929-full-fd65`). "First real numbers" has **not** happened: there is no calibration,
no macro dataset, and no face embedder, so there is no real number to report beyond MRZ/QR/diary
on generated fixtures. Sync round-trips within one device. **No clip has been built** and the
BOM says ~₹445, not ₹300.

## M1 — Android field app E2E (Week 2)
Theme: airplane-mode verdict on real phones.

| ID | Task | Owner | pd | DoD |
|---|---|---|---|---|
| M1.1 | Capture flow (quad-crop+manual, quality gates, GREY) + router | Android | 5 | 20-run capture drill, GREY triggers verified |
| M1.2 | TFLite detect+embed + align + 1:1 + passive liveness + latency overlay | Android+Vision | 5 | pairs bench on-device; medians logged |
| M1.3 | Wire math+QR+macro+diary+fusion+verdict UI + voice + demo mode | Android+Core | 5 | E2E stranger+trust, airplane, ×20 crash-free |
| M1.4 | Trust enroll (supervisor PIN)/verify/revoke/recheck-scheduler | Android | 3 | policy tests + UX walkthrough |
| M1.5 | Sync export/import + HMAC provisioning + merge-verify on 2 phones | Core+Android | 3 | A→B→A round-trip; quarantine path tested |
| M1.6 | Face threshold tune on pairs v1 + per-bucket report; macro re-tune on v1 | Vision | 3 | operating points in FUSION; histograms saved |
| M1.7 | APK size ≤35 MB + airplane-install test + battery sample | Android | 2 | CI size gate green; numbers logged |
| M1.8 | Milestone review | all | 1 | SPEC §6 M1 acceptance |

## M2 — Desktop console + cross-device intel (Week 3)
Theme: the post, not the phone.

| ID | Task | Owner | pd | DoD |
|---|---|---|---|---|
| M2.1 | CMP desktop shell + shared UI wiring (review/diary/sync/report screens) | Desktop+Core | 5 | runs Win+Linux from checkout |
| M2.2 | Desktop TFLite inference (same bytes) + Tess4J OCR + manual MRZ fallback | Desktop | 4 | ⚠️ **platform-limited.** No first-party TFLite JVM runtime exists; the build binds `ai.djl.tflite`, which has natives for `linux-x86_64` and `osx-x86_64` **only**. `windows-x86_64` and Apple-Silicon Mac are review-only with no on-device inference, permanently (BUILD.md §4, RISKS R4, STATUS R-T). Parity: the *decode* half is now mechanical — `BlazeFaceDesktopParityTest` requires the Android decode to be bit-identical to the desktop one on the real detector output in `eval/fixtures/face/raw_outputs/`. The *device* half is not: there is no SDK and no device, so the DoD below is unreachable as written |
| M2.3 | Case-bundle export/import + shift report + HQ flags mini-view | Desktop+Lead | 3 | bundle opens on clean machine; report prints |
| M2.4 | Watchlist create→file+QR→scan→match loop (both platforms) | Core+Android | 3 | E2E loop demoed + filmed |
| M2.5 | NFC spike result: real-chip test or honest stub + BAC unit tests | Android | 2 | spike closed; UI labeled accordingly |
| M2.6 | Multi-lane drill (2 phones + console, timed, queue sheet) | all | 2 | wall-clock medians; lane diagram validated |
| M2.7 | Packaging: Win/Linux zips (+Mac best-effort) + USB installer set | Desktop | 3 | clean-machine install verified |
| M2.8 | Milestone review + P1 cut decisions | all | 1 | cut list final |

**Tripwire M2-d3 (packaging): desktop packaging stalls → fallback: desktop runs via
`./gradlew :app-desktop:run` at finals (acceptable); installer effort capped.
⚠️ Taken by default — `packageDistributionForCurrentOS` was never implemented, and BUILD.md §3
records that.**

**Tripwire M2-d3 (inference): the desktop runtime is missing an OS. ⚠️ THIS TRIPWIRE HAS ALREADY
FIRED, and it is not a tripwire any more, it is a fact.** `BUILD.md` §4 used to describe a
per-OS "ships *review-only, no on-device inference*, clearly labeled" response, and the old
§7 troubleshooting row told you to go and find a `<os>` classifier. Both were based on a runtime
that does not exist. What is actually true:

- There is **no first-party TFLite JVM artifact**. `org.tensorflow:tensorflow-lite:2.17.0` is a
  1,411-byte jar whose whole content is a `<relocation>` POM to `com.google.ai.edge.litert:litert`,
  which is `<packaging>aar</packaging>`; no earlier version publishes a `.jar` at all; and no
  `<os>` classifier exists for any version. The AAR's own `.so` files are NDK-built for Bionic
  (`readelf -d` shows `NEEDED libc.so, liblog.so, libdl.so, libm.so`, no `libc.so.6` and no
  `ld-linux-x86-64.so.2`), so they cannot be loaded by a desktop glibc JVM.
- The build therefore binds **`ai.djl.tflite`** (Apache-2.0), which republishes the genuine
  `org.tensorflow.lite.*` Java API and publishes desktop JNI separately.
- It publishes natives for **`linux-x86_64` and `osx-x86_64` only** — two versions (`2.4.1`,
  `2.6.2`), both with exactly those two classifiers. `windows-x86_64`, `osx-aarch64`,
  `osx-arm64` and `linux-aarch64` are HTTP 404 for both.

**So: `windows-x86_64` and Apple-Silicon Mac are review-only, no on-device inference, and no
version bump fixes it.** The face layer reports `UNAVAILABLE` and a GREEN 1:1 face verdict is
unreachable on those machines. That is a build decision, not a research one, and it is now
recorded in `BUILD.md` §4, `DESIGN.md` D1, `RISKS.md` R4, `spikes/01-face-model.md` §4 and
`STATUS.md` R-T rather than in one place and contradicted in the others. The only route that
closes the gap is ORT-JVM — main jar 139,129,141 B at 1.22.0 (measured) — at the cost
of D1's "same bytes"; it is declined for now and is not scheduled in any milestone below.

## M3 — Hardening + red-team + frozen numbers (Week 4)
| ID | Task | Owner | pd | DoD |
|---|---|---|---|---|
| M3.1 | GREY tuning (sun/shade/night) + worn-doc + occlusion policies | Vision+Android | 3 | GREY-rate measured; policy in FUSION |
| M3.2 | RED-TEAM DAY (REDTEAM.md) + failure gallery + fix-or-acknowledge | all | 6 | gallery complete; P0 fixes merged |
| M3.3 | Freeze models/keys/thresholds (hashes in DESIGN §6); final `eval full` | Lead+Vision | 2 | frozen manifest; metrics deck = harness output |
| M3.4 | Q&A bank rehearsal aloud ×2; hostile panel (mentor plays judge) | Demo+all | 2 | gaps fixed in docs/slides |
| M3.5 | Spares + exhibit board + venue-lighting macro check plan | Demo | 2 | DEMO.md checklist green |
| M3.6 | Milestone review: go/no-go per gate | all | 1 | signed go |

## M4 — Finals 36h
Hour 0: **scope freeze** (P0-only board). Clean-checkout rebuilds; wiped-device USB installs; venue rehearsal ×3 (lighting recalibration if needed); roles briefed (DEMO.md §3). No new features. No threshold changes without full `eval smoke` + lead sign-off.

## M5 — Post-win pilot sketch (not built now)
1-post pilot → corridor sync → SSB-wide; MHA decisions: diary legal basis + retention order, UIDAI key rotation channel, ICAO PKD feed, CERT-In audit, registry APIs, longitudinal disparity audit. (One deck slide.)

## §7 Staffing variants
The 4-person and 8-person variants below are **historical planning options that no longer
apply.** The project is built by one person.

- **Solo (ACTUAL — this is the plan being executed):** the variant this file used to call "not
  recommended" is the one in force. What that costs, concretely and as measured on
  2026-09-29: the Android field app is written (34 source files) but has **never been
  compiled**; the desktop console runs; desktop packaging was never started; the face
  embedder is blocked on licensing and no amount of solo effort fixes that; D-MACRO, D-FACE,
  D-PASTE and D-SPOOF all have **zero media rows**; there is no SPECIMEN artwork, no built
  clip, and no red-team day. Priority therefore goes to (a) compiling `:app-android` on a real
  SDK, (b) starting the macro dataset, which has the longest lead time, and (c) the one
  licence/permissions decision that gates the entire face layer.
- **4 people / 4 weeks:** cut P1 entirely; desktop = run-from-source only (no packaging); one Android dev doubles desktop-review; red-team = half day. *(Not available.)*
- **8 people:** +dedicated QA (eval+red-team), +hardware/polish (clip v3, exhibit, video). *(Not available.)*
- **2-week crash:** M0(4d, spike-light) → M1(5d, Android only + file diary) → M2(2d, desktop run-only + sync file) → M3(3d, red-team-lite + freeze). Desktop packaging + NFC + Nepali cut to P2. Non-negotiable even in crash: harness, GREY policy, failure gallery, airplane demo. *(The harness and GREY policy exist; the failure gallery and the airplane demo do not, and the second needs a device that does not exist.)*

## Cut list (final unless M2 review reinstates a P1)
Age/gender · ELA-CNN · template registries · LLM · central biometrics · realtime net · predictive scores · ORT migration · RapidOCR-desktop · registry live calls · iOS · stamp-bridge automation · desktop voice.
