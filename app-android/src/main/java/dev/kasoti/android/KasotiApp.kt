package dev.kasoti.android

import android.app.Application
import android.content.Context
import dev.kasoti.android.field.CaptureQuality
import dev.kasoti.android.field.EvidenceAssembler
import dev.kasoti.android.field.FieldLog
import dev.kasoti.android.field.FieldLogEntry
import dev.kasoti.android.field.FixedClock
import dev.kasoti.android.field.IdFactory
import dev.kasoti.android.field.InMemoryEnrolmentStore
import dev.kasoti.android.field.MathLayer
import dev.kasoti.android.field.ScreeningSession
import dev.kasoti.android.platform.JcaDigest
import dev.kasoti.android.platform.MlKitOcrEngine
import dev.kasoti.android.platform.MlKitQrScanner
import dev.kasoti.android.platform.ModelPinLoader
import dev.kasoti.android.platform.ModelStore
import dev.kasoti.android.platform.defaultDigest
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import dev.kasoti.time.IsoDate
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * The application object: the single place every long-lived dependency is constructed and named.
 *
 * ## Why a container, and why it is hand-rolled
 *
 * Hilt would do this, and would add a plugin, an annotation processor and ~1 MB. The graph here
 * is a dozen objects with no scoping beyond "singleton for the process", every constructor is a
 * plain function call, and every one of them is unit-testable by calling it directly. So it is a
 * lazy holder, and [KasotiApp.graph] is the whole DI story.
 *
 * The reason it is *not* scattered across the activity is a correctness one, not a tidiness one:
 * the diary guard, the demo session and the trust store are the three objects that enforce
 * invariants I7 and FR-H5. If each activity could construct its own, "which demo session is
 * this?" would have five answers and a reset would not reset. One instance each, here.
 */
class KasotiApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }

    override fun onTerminate() {
        graph.stop()
        super.onTerminate()
    }
}

/**
 * The dependency graph.
 *
 * [start] is where anything that can fail is attempted, and [errors] is where a failure becomes a
 * `FindingCode` the UI can show with a recovery line. The rule throughout: **a missing model or a
 * missing OCR engine degrades a layer, it never fails the app and it never becomes a pass.**
 * That is the fail-closed half of invariant I12 and of FR-C2's "no clip, no verdict".
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    /** Everything that went wrong at start-up, as codes plus one operator-facing line each. */
    val errors = AtomicReference<List<StartupError>>(emptyList())

    private fun fail(code: dev.kasoti.fusion.FindingCode, detail: String) {
        errors.updateAndGet { it + StartupError(code, detail) }
    }

    val log: FieldLog = LogcatFieldLog

    /**
     * The clock.
     *
     * `java.time.Clock.systemUTC()` and nothing local: AGENTS.md §2 requires UTC ISO-8601
     * everywhere, and a device in a local timezone writing local timestamps into a diary that
     * merges across posts is how SYNC.md §2's clock-skew rule gets triggered by design.
     *
     * The skew *note* that AGENTS.md §5 asks for is `FieldClock`/`SkewReport` in the field layer:
     * the skew is measured against the last trusted reference and reported next to the verdict,
     * rather than being a comment in this file.
     */
    val clock: dev.kasoti.android.field.FieldClock = UtcFieldClock

    /**
     * The threshold registry.
     *
     * `ThresholdRegistry.defaults()` is the shipped `v1` set until provisioning writes
     * `thresholds.v1.json` (AGENTS.md §2 names that file as the single source of truth and
     * STATUS.md records that it does not exist yet). Reading a file that is not there would mean
     * a second code path for the registry, and a registry that can come from two places is a
     * registry that will one day come from the wrong one. Invariant I3 is satisfied either way:
     * the version and run id travel on every verdict.
     */
    val registry: ThresholdRegistry = ThresholdRegistry.defaults(
        version = "v1",
        runId = "app-${appContext.packageName}",
    )

    val quality = CaptureQuality(registry)
    val assembler = EvidenceAssembler(registry, log)
    val math = MathLayer(registry)
    val ids = IdFactory(clock)

    val screening: ScreeningSession = ScreeningSession(
        registry = registry,
        clock = clock,
        quality = quality,
        assembler = assembler,
        math = math,
        ids = ids,
        log = log,
    )

    /**
     * The device id (SYNC.md §6: per-device, re-provisioned on a swap).
     *
     * Written once to app-scoped storage. A `SharedPreferences` file is the right home because
     * it is app-scoped, needs no permission, and — with `allowBackup=false` and the extraction
     * rules — is not copied off the device, so two phones cannot end up sharing an id and
     * producing duplicate-looking records after a merge.
     */
    val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null) ?: ids.deviceId().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    val enrolmentStore = InMemoryEnrolmentStore()

    val trustPin: dev.kasoti.android.field.SupervisorPin by lazy {
        // The keystore-backed verifier. A *fixed* PIN is never shipped: the derived key is
        // created at provisioning from the supervisor's PIN and held in the keystore, and this
        // object is what compares against it. Until provisioning has run, every PIN is refused —
        // which means the trust lane cannot be enrolled on an un-provisioned handset, and that
        // is the correct failure direction for a shortcut.
        UnprovisionedPin
    }

    val trustLane: dev.kasoti.android.field.TrustLane = dev.kasoti.android.field.TrustLane(
        store = enrolmentStore,
        registry = registry,
        pinVerifier = trustPin,
    )

    val demoSession: dev.kasoti.android.field.DemoSession = dev.kasoti.android.field.DemoSession(
        registry = registry,
        today = clock.today(),
    )

    /** Hash-pinned model loading. `null` when the models are absent or refused (I12). */
    val modelLoader = ModelPinLoader(defaultDigest(), log)

    var verifiedDetector: dev.kasoti.android.platform.ModelPinLoader.VerifiedModel? = null
        private set
    var verifiedEmbedder: dev.kasoti.android.platform.ModelPinLoader.VerifiedModel? = null
        private set

    /** `null` when ML Kit could not be constructed; the app then says OCR is unavailable. */
    var ocr: MlKitOcrEngine? = null
        private set
    var qrScanner: MlKitQrScanner? = null
        private set

    fun start() {
        ocr = MlKitOcrEngine.createOrNull()
        if (ocr == null) {
            // Not a crash and not a pass: the MRZ step will report `G_OCRLOW` and the manual
            // entry fallback (BUILD.md §5) is the operator's route.
            fail(dev.kasoti.fusion.FindingCode.G_OCRLOW, "mlkit text recognition unavailable")
        }
        qrScanner = MlKitQrScanner.createOrNull()
        if (qrScanner == null) {
            fail(dev.kasoti.fusion.FindingCode.SYS_CAPTURE_FAILED, "mlkit barcode scanning unavailable")
        }
        loadModels()
    }

    /**
     * Verify the two model files (invariant I12, RT-E8).
     *
     * Each is pinned independently and a refusal names the model. Both are `null` after a
     * refusal, which makes the face layer ABSENT — and for every track where FACE is
     * load-bearing, that makes GREEN unreachable. A handset with tampered weights therefore
     * cannot produce a clean-looking report, and the operator is told so rather than being
     * handed a plausible similarity.
     */
    private fun loadModels() {
        // Read the pin holders at call time, not through a delegating object captured at class
        // init: provisioning writes `ProvisionedPins` after the graph exists, and a delegate
        // would have frozen the pre-provisioning "refuse everything" value forever.
        verifiedDetector = load(ModelStore.DETECT_ASSET, "blazeface_short", ProvisionedPins.blazefaceShort)
        verifiedEmbedder = load(ModelStore.EMBED_ASSET, "emb_v1", ProvisionedPins.embeddingV1)
    }

    private fun load(asset: String, name: String, pin: ModelPinSource): dev.kasoti.android.platform.ModelPinLoader.VerifiedModel? {
        if (!ModelStore.ensureCopied(appContext, asset, name)) {
            fail(dev.kasoti.fusion.FindingCode.SYS_MODEL_MISMATCH, "$name missing")
            return null
        }
        return try {
            val sha = pin.sha256()
            if (sha == null) {
                // No pin recorded yet: provisioning has not run. Refusing is correct, but it is
                // an *operational* refusal rather than tampering, so it is logged as such.
                fail(dev.kasoti.fusion.FindingCode.SYS_MODEL_MISMATCH, "$name has no recorded pin")
                return null
            }
            modelLoader.load(
                dev.kasoti.android.platform.ModelPin(
                    name = name,
                    file = File(ModelStore.file(appContext, name)),
                    sha256 = sha,
                    minimumBytes = pin.minimumBytes,
                ),
            )
        } catch (refusal: dev.kasoti.android.platform.ModelPinLoader.ModelRefusal) {
            fail(dev.kasoti.fusion.FindingCode.SYS_MODEL_MISMATCH, "${refusal.code} on $name")
            null
        }
    }

    fun stop() {
        ocr?.close()
        ocr = null
        qrScanner?.close()
        qrScanner = null
        verifiedDetector?.close()
        verifiedDetector = null
        verifiedEmbedder?.close()
        verifiedEmbedder = null
    }

    data class StartupError(val code: dev.kasoti.fusion.FindingCode, val detail: String)

    /**
     * A PIN source that refuses everything.
     *
     * This is the un-provisioned state, and refusing is the whole point: an un-provisioned
     * handset must not be able to enrol anybody, or "ship a build with a default PIN" becomes a
     * one-line change away from a trust lane anyone can enter. `PinKeyDeriver` +
     * `SupervisorPin.fixed` is the provisioned path; see the module README.
     */
    object UnprovisionedPin : dev.kasoti.android.field.SupervisorPin {
        override fun verify(pin: String): Boolean = false
    }

    private companion object {
        const val PREFS = "kasoti-device"
        const val KEY_DEVICE_ID = "device-id"
    }
}

/**
 * Where the model pins come from.
 *
 * In a release build this is a `const val` baked in at provisioning time — a pin that ships
 * *beside* the model is not a pin (`:platform`'s `ModelLoader` says so, and it is right). It is
 * an interface here so that a build without provisioning has a visible, empty source rather than
 * a silent default, and so the loader's refusal path is reachable in a test.
 */
fun interface ModelPinSource {
    /** @return the pinned SHA-256, or `null` when no pin was provisioned. */
    fun sha256(): String?

    /** A floor on plausible file size, to catch a truncated copy before the interpreter sees it. */
    val minimumBytes: Long get() = 1L
}

/** The UTC clock. See `AppGraph.clock` for why it is UTC and never local. */
object UtcFieldClock : dev.kasoti.android.field.FieldClock {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    override fun nowIso(): String = FORMAT.format(Instant.now())

    override fun today(): IsoDate = IsoDate.parse(nowIso().substring(0, 10))!!

    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** The production log sink. One line per event, tagged, scrubbed by `FieldLogFormat`. */
object LogcatFieldLog : FieldLog {
    override fun write(entry: FieldLogEntry) {
        android.util.Log.i("KASOTI", FieldLogFormat.render(entry))
    }
}

/** The pins recorded by `scripts/provision.sh`. `null` until that has run on this handset. */
object ProvisionedPins {

    var blazefaceShort: ModelPinSource = ModelPinSource { null }
    var embeddingV1: ModelPinSource = ModelPinSource { null }
    var svmPrintV1: ModelPinSource = ModelPinSource { null }
    var uidaiQrKeys: ModelPinSource = ModelPinSource { null }

    /** Read from a generated Kotlin source, or left null. Never a default. */
    fun isProvisioned(): Boolean =
        blazefaceShort.sha256() != null && embeddingV1.sha256() != null
}

/** The registry values the graph reads, re-exported so a settings screen cannot drift. */
object AppThresholds {
    val greyStreakLimit: ThresholdName = ThresholdName.GREY_STREAK_LIMIT
    val recheckProbability: ThresholdName = ThresholdName.RECHECK_P
}
