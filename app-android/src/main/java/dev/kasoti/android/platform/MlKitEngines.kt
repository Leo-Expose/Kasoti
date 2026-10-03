package dev.kasoti.android.platform

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.kasoti.android.field.VizFields
import dev.kasoti.qr.QrPayload

/**
 * ML Kit text recognition for the MRZ and the visual zone (DESIGN.md §3, D6).
 *
 * ## The bundled-model constraint is not negotiable
 *
 * The dependency is `com.google.mlkit:text-recognition` — the **bundled** artifact, not
 * `com.google.android.gms:play-services-mlkit-text-recognition`. The bundled one ships the
 * model inside the APK; the Play-Services one downloads it on first use, which means the first
 * screening on a device that has never been online silently produces `G_OCRLOW`, and the app
 * cannot tell that apart from a genuinely unreadable document. BUILD.md §4 lists this exact
 * symptom; the airplane-install test in CI is the check that it has not come back.
 *
 * Latin + Devanagari: `TextRecognizerOptions` is deliberately the *Latin* script option even
 * though the UI is Hindi, because the documents being read are ICAO 9303 machine-readable zones
 * and Aadhaar prints — all Latin. The Hindi in this app is the *interface*, handled by
 * `:core`'s `Messages` and `:ui`'s `FieldStrings`, not something OCR has to see.
 *
 * ## The seam
 *
 * This class is an Android implementation of the `dev.kasoti.platform.ocr.OcrEngine` shape from
 * DESIGN.md §3 — but it does not implement that interface literally, because `:platform` has no
 * `androidMain` and its `OcrEngine` is declared against `java.awt.image.BufferedImage`, which
 * does not exist on Android. The shape is preserved (`id`, `availability`, `recognize`) so the
 * future move to `:platform/androidMain` is a package move plus an `android.graphics.Bitmap`
 * parameter type, and so `:app-desktop` and this class can be read side by side.
 */
class MlKitOcrEngine private constructor(
    private val recognizer: TextRecognizer,
) {

    val id: String = ENGINE_ID

    /** One recognised line with its mean confidence, 0..1. */
    data class Line(val text: String, val confidence: Float, val left: Int, val top: Int, val width: Int, val height: Int)

    data class Recognition(
        val lines: List<Line>,
        val rawText: String,
        val meanConfidence: Float,
        /** Always `false`. Manual entry is the operator typing the rows (BUILD.md §5), and the
         *  distinction is greppable in the audit record six months later. */
        val manual: Boolean = false,
    ) {
        companion object {
            val EMPTY = Recognition(emptyList(), "", 0f)
        }
    }

    /** Whether the engine can actually run right now. Never throws from `recognize`. */
    fun availability(): Availability = Availability(
        available = true,
        detail = "com.google.mlkit:text-recognition (bundled model, no network)",
    )

    data class Availability(val available: Boolean, val detail: String)

    /**
     * Recognise the text in a bitmap.
     *
     * Blocking, and it must be called off the main thread: ML Kit's synchronous form is a
     * convenience for exactly this shape and it does real work. [dev.kasoti.android.capture.
     * FrameAnalyzer] runs it on the analysis executor.
     *
     * @return every line the engine saw, unfiltered. The MRZ extractor slices candidates out of
     *   this (see `dev.kasoti.android.field.MrzExtractor`) rather than the engine being asked
     *   for MRZ specifically — an engine asked to find an MRZ will find one whether or not there
     *   is one, which is the surest way to manufacture an `R-MATH-01`.
     *
     * `suspend` because [awaitCompat] is: ML Kit's `Task` has to be awaited, and
     * `suspendCancellableCoroutine` has no other legal home. The one caller
     * (`MainActivity.readMrz`) invokes this inside `withContext(Dispatchers.Default)`, so the OCR
     * still runs off the main thread and the ANR budget in NFR-R1 is unchanged.
     */
    suspend fun recognize(bitmap: Bitmap): Recognition = try {
        val image = InputImage.fromBitmap(bitmap, ROTATION)
        val result = recognizer.process(image).awaitCompat()
        val lines = result.textBlocks.flatMap { block ->
            block.lines.map { line ->
                Line(
                    text = line.text,
                    confidence = (line.confidence ?: 0f) / CONFIDENCE_SCALE,
                    left = line.boundingBox?.left ?: 0,
                    top = line.boundingBox?.top ?: 0,
                    width = line.boundingBox?.width() ?: 0,
                    height = line.boundingBox?.height() ?: 0,
                )
            }
        }
        Recognition(
            lines = lines,
            rawText = result.text,
            meanConfidence = if (lines.isEmpty()) 0f else lines.sumOf { it.confidence.toDouble() }.toFloat() / lines.size,
        )
    } catch (_: RuntimeException) {
        // ML Kit throws when the recogniser has been closed or the process is under memory
        // pressure. An OCR failure is `G_OCRLOW` and a retake, never a crash on a counter.
        Recognition.EMPTY
    }

    fun close() = recognizer.close()

    companion object {
        const val ENGINE_ID = "mlkit-text-recognition-bundled"

        /** `rotationDegrees` on an `ImageProxy` we have already applied. See `AndroidImaging`. */
        private const val ROTATION = 0

        /** ML Kit reports 0..100; `:platform`'s `OcrWord` normalises to 0..1. Same convention. */
        private const val CONFIDENCE_SCALE = 100f

        /**
         * @return an engine, or `null` when ML Kit could not be constructed.
         *
         * Construction is guarded because a device with a broken or missing native ML Kit
         * library should produce "OCR unavailable, and here is the retake instruction", not a
         * `NoClassDefFoundError` on the capture screen. The face layer degrades the same way.
         */
        fun createOrNull(): MlKitOcrEngine? = try {
            MlKitOcrEngine(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
        } catch (_: RuntimeException) {
            null
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }
}

/**
 * ML Kit barcode scanning for the Aadhaar secure QR and the watchlist QR (FR-Q1, FR-S2).
 *
 * Scoped to `QR_CODE` and to *data* payloads. That is not a performance choice: an Aadhaar
 * secure QR is a signed binary payload wrapped in a specific XML envelope, and parsing that is
 * `:core`'s job given the bytes. What this class does is hand over the raw bytes, unchanged.
 *
 * @param rawPayloads every code found, as the bytes the QR carried. A code whose payload was
 *   not read is *omitted* rather than passed as an empty array, so a caller cannot mistake
 *   "there was a QR and I could not read it" for "there was no QR". `QrLayer` treats an absent
 *   payload as an absent layer and a malformed one as a finding, and that distinction has to
 *   start here.
 */
class MlKitQrScanner private constructor(private val scanner: BarcodeScanner) {

    val id: String = ENGINE_ID

    suspend fun scan(bitmap: Bitmap): List<ByteArray> = try {
        scanner.process(InputImage.fromBitmap(bitmap, ROTATION))
            .awaitCompat()
            .mapNotNull { barcode ->
                when (barcode.valueType) {
                    Barcode.FORMAT_QR_CODE -> barcode.rawBytes
                    else -> null
                }
            }
    } catch (_: RuntimeException) {
        emptyList()
    } catch (_: UnsatisfiedLinkError) {
        emptyList()
    }

    fun close() = scanner.close()

    companion object {
        const val ENGINE_ID = "mlkit-barcode-bundled"
        private const val ROTATION = 0

        fun createOrNull(): MlKitQrScanner? = try {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            MlKitQrScanner(BarcodeScanning.getClient(options))
        } catch (_: RuntimeException) {
            null
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }
}

/**
 * A `QrPayload` built from scanned bytes, for `:core`'s `SecureQr`.
 *
 * The field extraction is **not** done here. `:core` owns the payload grammar
 * (`QrPayload.referenceId`, `.name`, `.dateOfBirth`), and duplicating that parse in the app
 * would be a second implementation of the one thing the QR layer must get exactly right.
 * [QrEnvelopeParser] is the app's only contribution, and it only decides whether the bytes are
 * the XML envelope `:core` expects or an opaque signed blob.
 */
class QrEnvelopeParser {

    data class Envelope(
        val payload: QrPayload,
        /** `true` when the bytes are the XML envelope rather than a bare signed blob. */
        val isXml: Boolean,
    )

    fun parse(bytes: ByteArray, version: Int = 1): Envelope? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8)
        val isXml = text.trimStart().startsWith("<")
        val fields = if (isXml) parseXmlFields(text) else emptyMap()
        // An unsigned XML payload still counts as `signed = false`; `:core` decides what that is
        // worth (FR-Q2) and the app does not get a vote.
        val hasSignature = SIGNATURE_MARKERS.any { text.contains(it) }
        return Envelope(
            payload = QrPayload(
                raw = bytes,
                version = version,
                fields = fields,
                signature = null,
                signed = hasSignature,
            ),
            isXml = isXml,
        )
    }

    /**
     * A deliberately small, non-validating tag reader.
     *
     * It is a *reader*, not a parser: it pulls `key="value"` pairs out of an XML fragment and
     * leaves every judgement to `:core`. A real XML parser on the app side would be a second
     * grammar, and a second grammar is a second set of bugs in the layer that must not have
     * any. Anything it cannot read comes back empty, which `:core` reads as "cannot evaluate" —
     * a GREY or an AMBER, never a pass.
     */
    private fun parseXmlFields(xml: String): Map<String, String> =
        TAG.findAll(xml).mapNotNull { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }.toMap()

    private companion object {
        val TAG = Regex("<([A-Za-z][A-Za-z0-9_]*)\\s[^>]*>([^<]*)</\\1>")
        val SIGNATURE_MARKERS = listOf("signature", "sig", "x-signature")
    }
}

/**
 * Reads what a visual-zone scan can give us, as [VizFields].
 *
 * Best-effort by design and honest about it: a missing field is left blank so
 * `VizMrzMatch` treats it as "no information" rather than "empty string", and the drift list
 * simply does not include that comparison. What must not happen — and what this class cannot do
 * — is inventing a value to compare against.
 */
class VizFieldReader {

    fun read(lines: List<MlKitOcrEngine.Line>): VizFields {
        if (lines.isEmpty()) return VizFields.EMPTY
        val text = lines.joinToString(" ") { it.text }
        return VizFields(
            name = NAME.find(text)?.groupValues?.get(1)?.trim()?.replace(WHITESPACE, " ").orEmpty(),
            dateOfBirth = DOB.find(text)?.groupValues?.get(1).orEmpty(),
            documentNumber = NUMBER.find(text)?.groupValues?.get(1).orEmpty(),
            expiryDate = EXPIRY.find(text)?.groupValues?.get(1).orEmpty(),
            issueDate = ISSUE.find(text)?.groupValues?.get(1).orEmpty(),
            aadhaarCandidate = AADHAAR.find(text)?.groupValues?.get(1),
        )
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        // Label-led extraction only. A bare 12-digit number anywhere on a card is not evidence
        // that the card *is* an Aadhaar; a field labelled as one is.
        val NAME = Regex("(?:name|नाम)\\s*[:：]?\\s*([A-Za-z][A-Za-z .'-]{1,40})")
        val DOB = Regex("(?:dob|date of birth|जन्म)\\s*(?:date)?\\s*[:：]?\\s*([0-9]{2}[/\\-][0-9]{2}[/\\-][0-9]{4}|[0-9]{4}[-/][0-9]{2}[-/][0-9]{2})")
        val NUMBER = Regex("(?:(?:passport|aadhaar|dl|driving|licen[cs]e|voter|id)[^:：\\n]{0,20}?(?:no|number|संख्या)\\s*[:：]?\\s*)([A-Za-z0-9]{4,20})", RegexOption.IGNORE_CASE)
        val EXPIRY = Regex("(?:expiry|expires|valid\\s*until)\\s*[:：]?\\s*([0-9]{2}[/\\-][0-9]{2}[/\\-][0-9]{4}|[0-9]{4}[-/][0-9]{2}[-/][0-9]{2})")
        val ISSUE = Regex("(?:issue[d]?)\\s*[:：]?\\s*([0-9]{2}[/\\-][0-9]{2}[/\\-][0-9]{4}|[0-9]{4}[-/][0-9]{2}[-/][0-9]{2})")
        val AADHAAR = Regex("\\b([0-9]{4}\\s?[0-9]{4}\\s?[0-9]{4})\\b")
    }
}

/**
 * `kotlinx-coroutines`' `await()` on a `Task`, without importing the extension name twice.
 *
 * ML Kit returns a `com.google.android.gms.tasks.Task`, from `play-services-tasks`.
 *
 * To be precise about the "no Play Services" claim, because it is a security-relevant one and
 * an imprecise version of it is worse than none: the bundled ML Kit artifacts depend on
 * `com.google.android.gms:play-services-tasks` (~50 KB of task plumbing, **no model**), so
 * that type is on the classpath transitively. What is *absent* is every
 * `com.google.android.gms:play-services-mlkit-*` artifact, which is the only thing that would
 * download a model at runtime. `scripts/airplane_install_test.sh` and the CI airplane build are
 * the checks that this distinction still holds; a dependency bump that swaps the artifact for
 * the Play Services one would pass a compile and fail that test, which is why it is written
 * down here.
 *
 * `await()` from `kotlinx-coroutines-play-services` is not used: it would add a second
 * Play Services artifact for one extension function. `addOnCompleteListener` needs nothing.
 */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitCompat(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            if (error != null) continuation.resumeWith(Result.failure(error))
            else continuation.resumeWith(Result.success(task.result))
        }
    }
