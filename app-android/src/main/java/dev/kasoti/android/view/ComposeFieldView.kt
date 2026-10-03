package dev.kasoti.android.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kasoti.android.R
import dev.kasoti.i18n.Language
import dev.kasoti.ui.AppState
import dev.kasoti.ui.FieldStrings
import dev.kasoti.ui.FieldView
import dev.kasoti.ui.FlowController
import dev.kasoti.ui.PermissionState
import dev.kasoti.ui.QuadMode
import dev.kasoti.ui.ScreenState
import dev.kasoti.ui.TrustCard
import dev.kasoti.ui.UiEvent
import dev.kasoti.ui.VerdictScreen
import dev.kasoti.ui.VerdictTone
import dev.kasoti.ui.blockingInstruction
import kotlin.math.roundToInt

/**
 * The `FieldView` implementation this module ships, and the metrics the screens below render with
 * (DESIGN.md §1, D7).
 *
 * ## What this file is, and what it is not
 *
 * A *renderer*. It reads an [AppState] and pushes [UiEvent]s back, and it is the second
 * implementation of the `dev.kasoti.ui.FieldView` interface — a desktop secondary-review pane
 * will be the first, and a Compose Multiplatform one may eventually replace both. It contains no
 * threshold, no verdict rule, and no `FindingCode` string lookup; every one of those lives in
 * `:core` or `:ui` and is unit-tested without a device.
 *
 * The policy lives in `:ui` precisely so that this failure is unreachable: two renderers, one of
 * which coloured a GREY like a RED. A jawan then tells a member of the public their document is
 * fraudulent because the sun was behind them, and no test fails — because the test was on the
 * other renderer.
 *
 * ## The four obligations from `FieldView`'s contract
 *
 *  1. [VerdictCard] draws `screen.headline` at [HEADLINE_SP] — the largest thing on the screen.
 *  2. Colour comes from [VerdictTone.paletteRole] via [palette]; `RETAKE` resolves to an
 *     *unfilled* neutral surface, and `RED` is the only filled block in the app.
 *  3. [Watermark] is drawn whenever `AppState.watermark` is non-null, over everything.
 *  4. No `Finding.message` is ever rendered — only `FindingRow.text`, which came from
 *     `Messages.of(code, language)`.
 *
 * ## The metrics are a `companion`, not a second `object` of the same name
 *
 * This file used to declare `object ComposeFieldView` for [HEADLINE_SP] & friends *and* `class
 * ComposeFieldView` for the [FieldView] implementation, which is a redeclaration — Kotlin allows
 * one top-level declaration per name per package, so only one of the two could ever exist and the
 * file did not compile at all. The constants moved into this class's `companion object` rather
 * than into a renamed holder because **every call site already spells them `ComposeFieldView.X`**,
 * including one KDoc in `QuadHandleOverlay.kt`, and a companion resolves through the class name —
 * so the fix is zero call-site churn and it keeps the name `FieldView.kt` in `:ui` already
 * documents as "the Android implementation of this interface".
 *
 * @param onDispatch the reducer. Injected rather than called directly so a test can observe
 *   every transition, and so the activity is not forced to expose `FlowController` as its own
 *   event handler.
 */
class ComposeFieldView(
    private val onDispatch: (AppState, UiEvent) -> AppState = { state, event -> FlowController.reduce(state, event) },
    private val onRender: (AppState) -> Unit = {},
) : FieldView {

    override fun render(state: AppState) = onRender(state)

    override fun dispatch(event: UiEvent) {
        // There is no state to reduce *from* here: `FieldView.dispatch` is the outbound
        // direction, and the state lives in the activity. This exists so a `FieldView` reference
        // is meaningful and so `RecordingFieldView` has a sibling with the same shape.
    }

    fun send(state: AppState, event: UiEvent): AppState = onDispatch(state, event)

    companion object {

        /**
         * The verdict type scale, in `sp`.
         *
         * `sp` and not `dp` so it respects the system font size, which is the accessibility
         * requirement in the review checklist. Large because SPEC §3 demands a *giant* verdict, and
         * because the largest thing on a screening screen should be the one thing an officer acts
         * on.
         */
        const val HEADLINE_SP = 96

        /** Finding lines, for the same reason. */
        const val BODY_SP = 20

        /**
         * 64dp minimum touch target.
         *
         * Material's 48dp is a *bare-hands* figure. SPEC §3 says this app is used with gloves on, in
         * dust, one-handed, by someone who is not looking down. A 48dp target is a mis-tap here, and
         * a mis-tap in this app is an accusation or a missed screening.
         */
        val MIN_TOUCH: Dp = 64.dp

        const val VERDICT_TAG = "verdict"
    }
}

/** The minimum touch target, applied to every interactive control below. */
private fun Modifier.tappable(): Modifier = this.defaultMinSize(
    minWidth = ComposeFieldView.MIN_TOUCH,
    minHeight = ComposeFieldView.MIN_TOUCH,
)

/** Applies a uniform square size, used for the language toggle. */
private fun Modifier.square(size: Dp): Modifier = this.size(size)

// ---------------------------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------------------------

/**
 * The single screen.
 *
 * The app has one primary flow with a bounded number of states rather than a nav graph,
 * because a nav back-stack on a device that may be killed by the OS between capture steps is
 * how a stranger's document gets handed back without a verdict. See `ScreenState` for that
 * argument; here it just means one `when`.
 */
/**
 * @param camera a slot for the live preview. A composable *slot* rather than a parameter of a
 *   concrete type, because the `PreviewView` needs a `Context` that Compose owns and because the
 *   whole binding has to be swappable for the desktop pane. `MainActivity` supplies
 *   [CameraSurface]; a desktop renderer supplies nothing and the row collapses.
 */
@Composable
fun KasotiScreen(
    state: AppState,
    on: (UiEvent) -> Unit,
    camera: @Composable () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize().background(PAGE)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ComposeFieldView.MIN_TOUCH / 4),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state.language, on)

            when (val screen = state.screen) {
                is ScreenState.Permissions -> PermissionCard(state.language, screen.permission, on)
                is ScreenState.Capturing -> {
                    // The preview is the screen's own content, not a decoration: a jawan who
                    // cannot see what the camera sees cannot follow any instruction on it.
                    camera()
                    CaptureCard(state, screen, on)
                }
                is ScreenState.Macro -> MacroCardScreen(state.language, screen.stage, on)
                is ScreenState.Verdict -> VerdictCard(screen.screen, on)
                ScreenState.Idle -> IdleCard(state, on)
                is ScreenState.Trust -> TrustCardScreen(state.language, screen.card, on)
            }

            state.transientError?.let { message -> ErrorBanner(message, on) }
        }

        Watermark(state.watermark)
    }
}

@Composable
private fun Header(language: Language, on: (UiEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = FieldStrings.of(FieldStrings.Key.APP_NAME, language),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        LanguageToggle(language, on)
    }
}

@Composable
private fun LanguageToggle(current: Language, on: (UiEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (language in Language.entries) {
            if (language == current) {
                Text(
                    text = language.code.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 18.dp),
                )
            } else {
                OutlinedButton(
                    onClick = { on(UiEvent.SetLanguage(language)) },
                    modifier = Modifier.square(ComposeFieldView.MIN_TOUCH),
                ) {
                    Text(language.code.uppercase(), fontSize = 16.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------------------------

/**
 * The permission card — the first thing a jawan sees on a device that has never been used.
 *
 * The copy states the *reason* and states the network claim explicitly, because the most common
 * refusal in a screening context is "you want my data" and the honest answer belongs on the
 * screen rather than in a training video.
 */
@Composable
private fun PermissionCard(language: Language, permission: PermissionState, on: (UiEvent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = FieldStrings.of(FieldStrings.Key.PERMISSION_TITLE, language),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = FieldStrings.of(FieldStrings.Key.PERMISSION_BODY, language), fontSize = 18.sp)

            if (permission.canPrompt) {
                Button(onClick = { on(UiEvent.RequestPermission) }, modifier = Modifier.tappable()) {
                    Text(FieldStrings.of(FieldStrings.Key.PERMISSION_GRANT, language), fontSize = 20.sp)
                }
            } else {
                // One calm line and a named recovery. DEMO.md §5: calm > clever, and an app
                // that nags a gloved operator into force-quitting it is worse than one that says
                // what to do.
                Text(
                    text = FieldStrings.of(FieldStrings.Key.PERMISSION_DENIED, language),
                    fontSize = 18.sp,
                    color = SECONDARY_EDGE,
                )
                OutlinedButton(onClick = { on(UiEvent.PermissionSettingsOpened) }, modifier = Modifier.tappable()) {
                    Text("Open settings", fontSize = 18.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Capture
// ---------------------------------------------------------------------------------------------

@Composable
private fun CaptureCard(state: AppState, screen: ScreenState.Capturing, on: (UiEvent) -> Unit) {
    val language = state.language
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val step = screen.progress.current
            if (step != null) {
                Text(text = step.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(text = step.hint, fontSize = 18.sp)
                Text(
                    text = FieldStrings.of(FieldStrings.Key.STEP_OF, language, step.ordinal, step.total),
                    fontSize = 15.sp,
                    color = MUTED,
                )

                // The quality bar, driven by the gate's own numbers and not by an animation.
                // An operator being told "hold still" must be told it by the same boolean that
                // will block the shutter, or the meter is a decoration.
                LinearProgressIndicator(
                    progress = { step.meter.score.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .semantics { contentDescription = "capture quality ${percent(step.meter.score)}" },
                )

                state.blockingInstruction()?.let { instruction ->
                    RetakeNotice(instruction, step.meter.causes)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { on(UiEvent.Shutter) },
                    // FR-C1: "GREY blocks advance on failure". The reducer enforces this too;
                    // disabling the control as well is a second, independent statement of the
                    // same rule, and a disabled button cannot be double-tapped past.
                    enabled = screen.progress.blocker == null,
                    modifier = Modifier.weight(1f).tappable(),
                ) {
                    Text(FieldStrings.of(FieldStrings.Key.QUALITY_CAPTURE, language), fontSize = 20.sp)
                }
                if (screen.progress.currentIndex > 0) {
                    OutlinedButton(onClick = { on(UiEvent.BackStep) }, modifier = Modifier.tappable()) {
                        Text("Back", fontSize = 18.sp)
                    }
                }
            }

            if (screen.quadMode == QuadMode.MANUAL) {
                // FR-C2's manual fallback. The four drag handles live on the PreviewView overlay
                // (`QuadHandleOverlay`), not here: this is the confirmation row, and the overlay
                // needs touch events with the image behind it.
                Text(
                    text = FieldStrings.of(FieldStrings.Key.QUAD_MANUAL, language),
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { on(UiEvent.SwitchQuadMode) }, modifier = Modifier.tappable()) {
                        Text(FieldStrings.of(FieldStrings.Key.QUAD_AUTO, language), fontSize = 18.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            on(UiEvent.AcceptQuad(screen.progress.current?.let { listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f) } ?: emptyList()))
                        },
                        modifier = Modifier.tappable(),
                    ) {
                        Text(FieldStrings.of(FieldStrings.Key.QUAD_ACCEPT, language), fontSize = 18.sp)
                    }
                }
            } else {
                OutlinedButton(onClick = { on(UiEvent.SwitchQuadMode) }, modifier = Modifier.tappable()) {
                    Text(FieldStrings.of(FieldStrings.Key.QUAD_MANUAL, language), fontSize = 18.sp)
                }
            }

            StepStrip(screen)
        }
    }
}

@Composable
private fun StepStrip(screen: ScreenState.Capturing) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (step in screen.progress.cards) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(if (step.done) CLEAR_EDGE else STEP_IDLE)
                    .semantics { contentDescription = "${step.label} ${step.title}" },
            )
        }
    }
}

/**
 * The retake notice.
 *
 * Neutral chrome with a single warm left edge, never a filled block — the same policy
 * [VerdictTone.RETAKE] encodes, expressed in the one place the operator is looking. An
 * instruction, with the visual weight of a camera glyph; not a stop sign.
 */
@Composable
private fun RetakeNotice(instruction: String, causes: List<dev.kasoti.fusion.FindingCode>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RETAKE_SURFACE)
            .border(1.dp, RETAKE_EDGE, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(4.dp).height(44.dp).background(RETAKE_BAND))
        Column {
            Text(text = instruction, fontSize = ComposeFieldView.BODY_SP.sp, fontWeight = FontWeight.Medium)
            if (causes.isNotEmpty()) {
                Text(
                    text = causes.joinToString(", ") { it.name },
                    fontSize = 12.sp,
                    color = MUTED,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Verdict — the screen that matters
// ---------------------------------------------------------------------------------------------

/**
 * The verdict card. The single most important composable in the app.
 *
 * Order, top to bottom, and the order is the argument:
 *
 *  1. the **headline** at [ComposeFieldView.HEADLINE_SP] — the glanceable word;
 *  2. the **tone surface** — filled for RED, unfilled neutral for GREY; that difference is what
 *     makes the two distinguishable from three metres away on a projector;
 *  3. the **action** — one button, glove-sized, saying what to do next;
 *  4. the findings, each with its evidence reference, so a supervisor can go and look;
 *  5. the layer table, so "RED because the check digit failed" and "RED because five layers ran
 *     and one disagreed" are visibly different conversations;
 *  6. the policy fingerprint, small — it is there for the audit, not for the officer.
 */
@Composable
fun VerdictCard(screen: VerdictScreen, on: (UiEvent) -> Unit) {
    val colors = palette(screen.tone)
    val language = Language.ENGLISH

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .border(if (screen.tone.isNeutralSurface) 2.dp else 0.dp, colors.edge, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .testTag(ComposeFieldView.VERDICT_TAG)
            .semantics {
                // One combined description, not a tree of nodes: a screen-reader user needs
                // "verdict DO NOT CLEAR" as one utterance, not the word DO, then NOT, then CLEAR.
                contentDescription = "Verdict ${screen.headline}"
                liveRegion = LiveRegionMode.Assertive
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = screen.headline,
            fontSize = ComposeFieldView.HEADLINE_SP.sp,
            fontWeight = FontWeight.Black,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (screen.demoMode) {
            // A red banner as well as the watermark. The watermark can be lost off-axis in a
            // photograph; the banner cannot, and this is the one place a wrong impression is
            // worth shouting about.
            Text(
                text = DEMO_BANNER,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = STOP_EDGE,
            )
        }

        if (screen.isRetake) {
            // A retake shows *instructions* under a *neutral* headline, in that order, and no
            // findings table — because there are no findings. This is FUSION.md §1 rendered.
            for (row in screen.retakeInstructions) {
                RetakeNotice(row.text, listOf(row.code))
            }
        } else {
            for (row in screen.findings) {
                FindingLine(
                    severity = row.severityText,
                    text = row.text,
                    evidenceRef = row.evidenceRef,
                    color = palette(row.tone).onSurface,
                )
            }
        }

        if (screen.carriedOver.isNotEmpty()) {
            Text(
                text = "Already seen on the previous capture:",
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
            )
            for (row in screen.carriedOver) {
                FindingLine(row.severityText, row.text, row.evidenceRef, MUTED)
            }
        }

        if (screen.layers.isNotEmpty()) LayerTable(screen)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val label = when {
                screen.isRetake -> FieldStrings.of(FieldStrings.Key.ACTION_RETAKE, language)
                screen.supervisorRequired -> FieldStrings.of(FieldStrings.Key.ACTION_SUPERVISOR, language)
                else -> FieldStrings.of(FieldStrings.Key.ACTION_NEXT, language)
            }
            Button(
                onClick = { on(if (screen.isRetake) UiEvent.Shutter else UiEvent.NextPerson) },
                modifier = Modifier.weight(1f).tappable(),
            ) {
                Text(label, fontSize = ComposeFieldView.BODY_SP.sp)
            }
            OutlinedButton(onClick = { on(UiEvent.RepeatVoice) }, modifier = Modifier.tappable()) {
                Text(FieldStrings.of(FieldStrings.Key.ACTION_REPEAT_VOICE, language), fontSize = 16.sp)
            }
        }

        Text(text = screen.policyLine, fontSize = 11.sp, color = MUTED, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FindingLine(severity: String, text: String, evidenceRef: String, color: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = text,
            fontSize = ComposeFieldView.BODY_SP.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
        Text(
            // The evidence ref is small, quiet, and always present: it is an identifier to be
            // copied, not read, and it must never be the thing that draws the eye.
            text = "$severity · $evidenceRef",
            fontSize = 13.sp,
            color = MUTED,
        )
    }
}

@Composable
private fun LayerTable(screen: VerdictScreen) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = "Checks", fontWeight = FontWeight.Medium, fontSize = 17.sp)
        for (layer in screen.layers) {
            Text(
                text = buildString {
                    append(layer.layer.name.lowercase())
                    append("  ")
                    append(layer.ref.substringAfter('/'))
                    // `●` load-bearing, `○` auxiliary: FUSION.md §7, readable at a glance.
                    append(if (layer.loadBearing) "  ●" else "  ○")
                },
                fontSize = 14.sp,
                color = MUTED,
                modifier = Modifier.semantics {
                    contentDescription = "layer ${layer.layer.name.lowercase()}, ${layer.ref}"
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Watermark, macro, idle, trust, errors
// ---------------------------------------------------------------------------------------------

/**
 * The DEMO watermark (FR-C5).
 *
 * Diagonal, low-alpha, and drawn **last** so it sits over everything including the headline. A
 * watermark that a card can cover is not a watermark, and the one thing it must achieve is that a
 * photograph of the projector screen cannot be mistaken for a real screening. Rotated 12° rather
 * than 45° so it does not obscure the word the operator is actually reading while still being
 * unmissable in a photograph.
 */
@Composable
private fun Watermark(text: String?) {
    if (text == null) return
    Box(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Demo mode" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 140.sp,
            fontWeight = FontWeight.Black,
            color = STOP_EDGE.copy(alpha = 0.18f),
            modifier = Modifier.rotate(-12f),
        )
    }
}

@Composable
private fun MacroCardScreen(language: Language, stage: dev.kasoti.ui.MacroCard, on: (UiEvent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = FieldStrings.of(FieldStrings.Key.STEP_MACRO, language),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = FieldStrings.of(FieldStrings.Key.STEP_MACRO_HINT, language), fontSize = 18.sp)
            Text(
                text = FieldStrings.of(FieldStrings.Key.MACRO_USE_CLIP, language),
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            )

            SharpnessBar(FieldStrings.of(FieldStrings.Key.MACRO_PHOTO_ZONE, language), stage.photoZoneSharpness)
            SharpnessBar(FieldStrings.of(FieldStrings.Key.MACRO_TEXT_ZONE, language), stage.textZoneSharpness)

            Text(
                text = if (stage.focusLocked) {
                    FieldStrings.of(FieldStrings.Key.MACRO_FOCUS_LOCKED, language)
                } else {
                    FieldStrings.of(FieldStrings.Key.MACRO_FOCUS_TAP, language)
                },
                color = MUTED,
                fontSize = 16.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { on(UiEvent.SetClipUsed(!stage.clipUsed)) }, modifier = Modifier.tappable()) {
                    Text(if (stage.clipUsed) "Clip ON" else "Clip OFF", fontSize = 18.sp)
                }
                Button(
                    onClick = { on(UiEvent.Shutter) },
                    // Both FR-C3 patches, both focused. `MacroStage.canAdvance` is the
                    // authoritative check; this mirrors it so the operator is not offered a
                    // shutter that the field layer would refuse.
                    enabled = stage.ready,
                    modifier = Modifier.weight(1f).tappable(),
                ) {
                    Text(FieldStrings.of(FieldStrings.Key.QUALITY_CAPTURE, language), fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun SharpnessBar(label: String, value: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "${FieldStrings.of(FieldStrings.Key.MACRO_SHARPNESS, Language.ENGLISH)} · $label", fontSize = 14.sp)
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .semantics { contentDescription = "sharpness $label" },
        )
    }
}

@Composable
private fun IdleCard(state: AppState, on: (UiEvent) -> Unit) {
    val language = state.language
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The network claim, on the idle screen, before anything has happened. It is the
            // single most-asked question about a device that photographs identity documents, and
            // answering it with a sentence on the screen costs nothing.
            Text(
                text = NO_NETWORK_CLAIM,
                fontSize = 17.sp,
                color = CLEAR_EDGE,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { on(UiEvent.Shutter) }, modifier = Modifier.weight(1f).tappable()) {
                    Text(FieldStrings.of(FieldStrings.Key.QUALITY_CAPTURE, language), fontSize = 20.sp)
                }
                OutlinedButton(onClick = { on(UiEvent.ToggleDemoMode) }, modifier = Modifier.tappable()) {
                    Text(FieldStrings.of(FieldStrings.Key.DEMO_LOAD, language), fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun TrustCardScreen(language: Language, card: TrustCard, on: (UiEvent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (card.enrolled) {
                    FieldStrings.of(FieldStrings.Key.TRUST_FAST, language)
                } else {
                    FieldStrings.of(FieldStrings.Key.TRUST_ENROL, language)
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            if (card.enrolled) {
                Text(text = "re-verify in ${card.daysUntilReverify} days", color = MUTED, fontSize = 16.sp)
            }
            if (card.recheckScheduled) {
                Text(
                    text = FieldStrings.of(FieldStrings.Key.TRUST_RECHECK, language),
                    color = SECONDARY_EDGE,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { on(UiEvent.RequestRecheck) }, modifier = Modifier.tappable()) {
                    Text(FieldStrings.of(FieldStrings.Key.TRUST_RECHECK, language), fontSize = 16.sp)
                }
                OutlinedButton(onClick = { on(UiEvent.Enrol(FIELD_PIN)) }, modifier = Modifier.tappable()) {
                    Text(FieldStrings.of(FieldStrings.Key.TRUST_ENROL, language), fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, on: (UiEvent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = message, color = SECONDARY_EDGE, fontSize = 16.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { on(UiEvent.DismissError) }, modifier = Modifier.tappable()) {
                Text("OK", fontSize = 16.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------------------------

/** A resolved palette: the three roles a verdict needs, plus whether it is filled. */
data class Palette(val surface: Color, val onSurface: Color, val edge: Color, val filled: Boolean)

/**
 * The policy → colour mapping.
 *
 * The one line that must never be wrong:
 *
 *     `RETAKE` is NOT filled, and its surface is the same neutral as the page.
 *
 * `STOP` is the only filled, saturated block in the app. That is the whole visual argument, and
 * it is what makes a GREY unmistakable at a distance on a projector. [VerdictTone] is the
 * policy and is unit-tested; this function is its presentation and is deliberately thin, so
 * there is nowhere for a second opinion to creep in.
 */
fun palette(tone: VerdictTone): Palette = when (tone) {
    VerdictTone.STOP -> Palette(SURFACE_STOP, ON_STOP, STOP_EDGE, filled = true)
    VerdictTone.RETAKE -> Palette(RETAKE_SURFACE, ON_RETAKE, RETAKE_EDGE, filled = false)
    VerdictTone.SECONDARY -> Palette(SURFACE_SECONDARY, SECONDARY_EDGE, SECONDARY_EDGE, filled = false)
    VerdictTone.CLEAR -> Palette(SURFACE_CLEAR, CLEAR_EDGE, CLEAR_EDGE, filled = false)
}

// Mirrors res/values/colors.xml. Kept as constants rather than `ColorResource` lookups because
// the palette is needed from non-composable code (`VoiceReadout`'s checks, tests) and because a
// `when` over an enum cannot be exhaustive-checked against a resource name.
private val PAGE = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF5F6368)
private val STEP_IDLE = Color(0xFFE0E0E0)
private val SURFACE_STOP = Color(0xFFB3261E)
private val ON_STOP = Color(0xFFFFFFFF)
private val STOP_EDGE = Color(0xFF7F1D1A)
private val RETAKE_SURFACE = Color(0xFFF4F4F2)

/**
 * `internal`, not `private`: `QuadHandleOverlay.kt` draws the retake outline and the corner
 * handles with these two, and they are the same *policy* colours the verdict card uses — a
 * top-level `private` is file-scoped, so the overlay could not see them and failed to compile.
 * Widening the visibility is what keeps one definition instead of two copies of a colour that
 * means "ask again". The rest of this palette stays `private`: nothing outside this file uses it.
 */
internal val ON_RETAKE = Color(0xFF1C1B1F)
internal val RETAKE_EDGE = Color(0xFF5F6368)
private val RETAKE_BAND = Color(0xFF8A6A00)
private val SURFACE_SECONDARY = Color(0xFFFFF6E0)
private val SECONDARY_EDGE = Color(0xFF4A3600)
private val SURFACE_CLEAR = Color(0xFFE8F5E9)
private val CLEAR_EDGE = Color(0xFF0D3B10)

private const val DEMO_BANNER = "DEMO MODE — not a real screening"
private const val NO_NETWORK_CLAIM = "KASOTI does not use the network. Everything here runs on this device."

/**
 * The PIN placeholder the trust card sends.
 *
 * A real implementation opens a numeric keypad and never carries a PIN through a `UiEvent` at
 * all; [dev.kasoti.ui.UiEvent.Enrol] takes the string so the *reducer* can be tested without a
 * keyboard, and the demo path uses this. Shipping a real PIN through an event object is a real
 * risk — events are the easiest thing to log — so the field is documented here as demo-only and
 * the trusted `SupervisorPin` implementation in `:app-android` is what must be bound in release.
 */
private const val FIELD_PIN = ""

private fun percent(value: Float): Int = (value.coerceIn(0f, 1f) * 100).roundToInt()
