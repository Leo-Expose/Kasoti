package dev.kasoti.desktop.screen

import dev.kasoti.checks.DateLogic
import dev.kasoti.checks.FormatVerdict
import dev.kasoti.checks.VizMrzMatch
import dev.kasoti.factory.GrayImage
import dev.kasoti.factory.MacroGate
import dev.kasoti.fusion.DriftScore
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.MathEvidence
import dev.kasoti.fusion.MacroEvidence
import dev.kasoti.fusion.ProcessLabel
import dev.kasoti.fusion.QrEvidence
import dev.kasoti.fusion.QualityReport
import dev.kasoti.mrz.MrzResult
import dev.kasoti.platform.imaging.NormRect
import dev.kasoti.qr.KeyRing
import dev.kasoti.qr.QrCrossCheck
import dev.kasoti.qr.QrPayload
import dev.kasoti.qr.SecureQr
import dev.kasoti.qr.SigResult
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.time.CalendarDate
import dev.kasoti.time.IsoDate
import java.nio.file.Path
import java.util.Base64

/**
 * The individual cascade layers.
 *
 * Split out of [ScreeningCascade] because each one is a separate question with a separate
 * failure mode, and because a 460-line file is a file nobody re-reads before changing a
 * threshold comparison. They are `internal` extensions rather than private methods so that
 * the orchestration above reads as a list of layers and each layer reads on its own.
 */
internal fun ScreeningCascade.readImage(
    image: Path,
    layers: MutableList<LayerStatus>,
    warnings: MutableList<String>,
): GrayImage {
    val gray = try {
        imaging.readGray(image)
    } catch (e: Exception) {
        layers += LayerStatus("image", LayerStatus.State.UNAVAILABLE, "decode failed: ${e.message}")
        warnings += "image could not be decoded; every layer is unavailable"
        return GrayImage.fromBytes(1, 1, ByteArray(1))
    }
    layers += LayerStatus("image", LayerStatus.State.RAN, "${gray.width}x${gray.height} gray, mean ${grayStr(gray.mean)}")
    return gray
}

/**
 * Capture quality (FR-C1, FUSION.md §4).
 *
 * Only brightness is assessed, because it is the one property of a *file* that means
 * something. Blur, glare, pose and occlusion need a capture pipeline that records the
 * exposure and the landmark geometry; asserting them from a JPEG would be inventing
 * evidence. Those causes are reported as UNAVAILABLE so a low-quality capture is never
 * silently read as a good one.
 */
internal fun ScreeningCascade.assessQuality(
    gray: GrayImage,
    request: ScreeningRequest,
    layers: MutableList<LayerStatus>,
): QualityReport {
    val brightness = gray.mean * 255f
    val min = registry[ThresholdName.Q_BRIGHT_MIN].toFloat()
    val max = registry[ThresholdName.Q_BRIGHT_MAX].toFloat()
    val causes = buildList {
        if (brightness < min) add(FindingCode.G_DARK)
        if (brightness > max) add(FindingCode.G_GLARE)
    }
    layers += LayerStatus(
        "quality",
        LayerStatus.State.RAN,
        if (causes.isEmpty()) {
            "brightness ${grayStr(brightness)} within [${grayStr(min)}, ${grayStr(max)}]"
        } else {
            "brightness ${grayStr(brightness)} outside [${grayStr(min)}, ${grayStr(max)}] — retake"
        },
    )
    layers += LayerStatus(
        "quality.appearance",
        LayerStatus.State.UNAVAILABLE,
        "blur/glare/pose need the capture pipeline; a file import cannot attest them",
    )
    return QualityReport(passed = causes.isEmpty(), causes = causes, brightness = brightness)
}

/** Layer 1 — math: ICAO check digits, calendar logic, VIZ↔MRZ drift. */
internal fun ScreeningCascade.runMath(
    request: ScreeningRequest,
    quality: QualityReport,
    layers: MutableList<LayerStatus>,
    warnings: MutableList<String>,
): MathEvidence? {
    val mrz = parseMrz(request)
    if (mrz == null) {
        layers += LayerStatus("math", LayerStatus.State.SKIPPED, "no MRZ supplied (sidecar `mrz` or --mrz)")
        return null
    }
    if (!mrz.usable) {
        layers += LayerStatus("math", LayerStatus.State.RAN, "MRZ unreadable: ${mrz.structuralErrors.joinToString("; ")}")
        return MathEvidence(allChecksPassed = false, failedFields = setOf("structure"), impossibleDate = true)
    }

    val failures = mrz.failedChecks.map { it.field.name }.toSet()
    val dateOutcomes = DateLogic.evaluate(
        birthDate = mrz.birthDate.toIso(request.today),
        expiryDate = mrz.expiryDate.toIso(request.today),
        issueDate = request.sidecar.issueDate.toIso(request.today),
        today = request.today,
    )
    val dateFailed = dateOutcomes.any { it.verdict == FormatVerdict.FAIL }

    val drift = driftScore(request, mrz)

    layers += LayerStatus(
        "math",
        LayerStatus.State.RAN,
        buildString {
            append("${mrz.format}; ")
            append(if (failures.isEmpty()) "all check digits pass" else "check digit failure in ${failures.sorted()}")
            if (dateFailed) append("; date logic failed (${dateOutcomes.first { it.verdict == FormatVerdict.FAIL }.reason})")
        },
    )
    return MathEvidence(
        allChecksPassed = failures.isEmpty() && !dateFailed,
        failedFields = failures,
        expired = dateOutcomes.any { it.reason.contains("expired") },
        impossibleDate = dateFailed,
        vizDrift = drift,
    )
}

/**
 * Compare the printed zone against the MRZ.
 *
 * Only a *disagreement* produces a drift record. Returning an identical-pair `DriftScore`
 * for a field that matched would put a `score >= 1f` entry in the evidence for every single
 * screening, and a case bundle full of non-findings is a case bundle nobody reads.
 */
private fun ScreeningCascade.driftScore(request: ScreeningRequest, mrz: MrzResult): DriftScore? {
    val sidecar = request.sidecar
    if (sidecar.printName.isBlank() && sidecar.printDob.isBlank()) return null
    if (sidecar.printName.isNotBlank()) {
        val score = VizMrzMatch.nameScore(sidecar.printName, mrz.name.normalized)
        if (score < 1f) return VizMrzMatch.drift("name", score, sidecar.printName, mrz.name.normalized)
    }
    if (sidecar.printDob.isNotBlank()) {
        val score = VizMrzMatch.dateScore(sidecar.printDob, mrz.birthDate.orEmpty(), request.today.year)
        if (score < 1f) return VizMrzMatch.drift("dob", score, sidecar.printDob, mrz.birthDate.orEmpty())
    }
    return null
}

/** Layer 2 — the secure QR (FR-Q1 signed, FR-Q2 unsigned consistency only). */
internal fun ScreeningCascade.runQr(
    request: ScreeningRequest,
    layers: MutableList<LayerStatus>,
    warnings: MutableList<String>,
): QrEvidence? {
    val sidecar = request.sidecar
    if (!sidecar.hasQr) {
        layers += LayerStatus("qr", LayerStatus.State.SKIPPED, "no QR supplied")
        return null
    }

    val raw = decodeBase64(sidecar.qrRawBase64)
    if (raw == null) {
        layers += LayerStatus("qr", LayerStatus.State.RAN, "unsigned QR (claims only, no signed body)")
        return QrEvidence(
            present = true,
            signed = false,
            signatureValid = false,
            unsignedFieldsPresent = sidecar.qrFields.isNotEmpty(),
        )
    }
    val signature = decodeBase64(sidecar.qrSignatureBase64)
    val payload = QrPayload(
        raw = raw,
        version = 1,
        fields = sidecar.qrFields,
        signature = signature,
        signed = signature != null,
    )
    if (signature == null) {
        layers += LayerStatus("qr", LayerStatus.State.RAN, "signed payload with no signature — treated as unsigned")
        return QrEvidence(present = true, signed = false, signatureValid = false, unsignedFieldsPresent = true)
    }

    val result = verifyWithRing(payload, sidecar)
    val mismatches = QrCrossCheck.compare(payload, sidecar.printName, sidecar.printDob)
    if (mismatches.isNotEmpty()) {
        warnings += "QR disagrees with the printed document on ${mismatches.map { it.field }}"
    }
    val detail = when (result) {
        is SigResult.Valid -> "signature verified with key '${result.keyId}'"
        is SigResult.StaleKeys -> "signature verified with '${result.keyId}', but that key is past rotation"
        is SigResult.UnknownKey -> "no bundled key verified this signature"
        SigResult.Invalid -> "signature invalid"
    }
    layers += LayerStatus("qr", LayerStatus.State.RAN, "$detail; ${mismatches.size} field mismatch(es)")
    return QrEvidence(
        present = true,
        signed = true,
        signatureValid = result is SigResult.Valid || result is SigResult.StaleKeys,
        keysStale = result is SigResult.StaleKeys,
        mismatches = mismatches.map { it.field to (it.qrValue to it.printValue) },
    )
}

/**
 * Build the key ring from the sidecar.
 *
 * The production ring is a provenance-reviewed file that does not exist yet — DESIGN.md §6
 * keeps the `uidai_prod` slot empty on purpose. A test key may be supplied per screening,
 * and it is the *test* slot: a verification against it is reported with its key id so nobody
 * can mistake a simulated result for a real one.
 */
private fun ScreeningCascade.verifyWithRing(payload: QrPayload, sidecar: Sidecar): SigResult {
    val keyBytes = decodeBase64(sidecar.qrX509KeyBase64) ?: return SigResult.UnknownKey
    val keyId = sidecar.qrKeyId.ifBlank { ScreeningCascade.DEFAULT_TEST_KEY_ID }
    val ring = KeyRing(
        keys = mapOf(
            keyId to KeyRing.KeyEntry(
                keyId = keyId,
                x509PublicKey = keyBytes,
                issuedAt = "1970-01-01",
                expiresAt = null,
                provenance = "supplied by the screening sidecar — provenance NOT verified",
            ),
        ),
    )
    return SecureQr.verify(payload, ring, verifier)
}

/** Layer 3 — print process from the macro patches (FR-F1). */
internal fun ScreeningCascade.runMacro(
    request: ScreeningRequest,
    layers: MutableList<LayerStatus>,
    warnings: MutableList<String>,
): MacroEvidence? {
    val model = request.macroModel
    if (model == null) {
        // No model at all. The layer did not run, and the row has to say that in the state
        // column rather than in prose after a reading that was never produced.
        layers += LayerStatus(
            "macro",
            LayerStatus.State.UNAVAILABLE,
            "no macro model provisioned — search ${request.modelSearchNote}",
        )
        return null
    }
    if (!model.discriminative) {
        // A model that loaded and has no opinion is *not* a layer that ran. It used to be
        // reported as RAN with `model=OFFSET, margin=0.000`, which is an argmax over a row of
        // zero weights wearing the shape of a measurement.
        layers += LayerStatus(
            "macro",
            LayerStatus.State.UNAVAILABLE,
            "${model.standing} (${model.provenance}) — no discriminative power, so no process " +
                "reading; provision ${SvmModelFile.DEFAULT_FILE_NAME} to enable this layer",
        )
        return null
    }
    val classifier = model.classifier
    val photoRect = request.sidecar.photoZone?.toNormRect()
    val textRect = request.sidecar.textZone?.toNormRect()
    if (photoRect == null && textRect == null) {
        layers += LayerStatus("macro", LayerStatus.State.SKIPPED, "no photoZone/textZone in the sidecar")
        return null
    }

    val rgb = try {
        imaging.readRgb(request.image)
    } catch (e: Exception) {
        layers += LayerStatus("macro", LayerStatus.State.UNAVAILABLE, "decode failed: ${e.message}")
        return null
    }

    // Read through `MacroGate`, the same `:core` gate `MacroStage` uses on Android, so the
    // console and the phone apply one floor to the same scores. This used to be a local
    // `margin < floor ? UNKNOWN : label` expression, which is a third implementation of a safety
    // rule and the one most likely to drift.
    val floor = registry[ThresholdName.MACRO_MARGIN_AMBER].toFloat()
    val photo = photoRect?.let {
        MacroGate.read(classifier, imaging.centreCropGray(rgb, it, ScreeningCascade.MACRO_PATCH_SIZE), registry)
    }
    val text = textRect?.let {
        MacroGate.read(classifier, imaging.centreCropGray(rgb, it, ScreeningCascade.MACRO_PATCH_SIZE), registry)
    }

    // TODO(M2,@vision): `DocAggregator.aggregate` is the document-level reading; the
    // console compares the two patches itself so the layer status can name both. Swap to
    // DocAggregator once the fusion package's aggregation lands with the real
    // `FusionEngine`.
    warnings += "macro read from the frame; run `kasoti macro` to collect labelled patches for training"
    layers += LayerStatus(
        "macro",
        LayerStatus.State.RAN,
        "photo=${photo.reading()} text=${text.reading()} clip=${request.clipUsed} " +
            "(under the margin floor a patch reads UNKNOWN, not its best guess)",
    )
    return MacroEvidence(
        photoZoneLabel = photo?.label.toFusion(),
        photoZoneMargin = photo?.margin ?: 0f,
        textZoneLabel = text?.label.toFusion(),
        textZoneMargin = text?.margin ?: 0f,
        clipUsed = request.clipUsed,
    )
}

/**
 * The label as it should be *read*, not as the classifier emitted it.
 *
 * FUSION.md §5: a patch whose margin is under the floor carries no information, so it is
 * shown as `UNKNOWN`. Printing the raw top-of-list class would put "OFFSET" in front of an
 * officer for a model that has no opinion at all — which is exactly what the untrained
 * demo stub returns, every time.
 */
private fun dev.kasoti.factory.MacroReading?.reading(): String {
    val result = this ?: return "UNKNOWN(no patch)"
    // `MacroGate` has already applied the floor, so `label` is what the system acts on and
    // `patch.label` is what the model said. Both are printed, because "the model said OFFSET and
    // the system is not claiming it" and "the model is confident" are different conversations.
    val qualifier = if (result.noModel) " (no model)" else ""
    return "${result.label.name}(margin=${grayStr(result.margin)}, model=${result.patch.label.name})$qualifier"
}

private fun Sidecar.RectSpec.toNormRect() = NormRect(x, y, width, height)

/** The two `ProcessLabel` enums are parallel vocabularies; keep the mapping in one place. */
private fun dev.kasoti.factory.ProcessLabel?.toFusion(): ProcessLabel =
    ProcessLabel.valueOf(this?.name ?: "UNKNOWN")

/**
 * `YYMMDD` from an MRZ, read under the same ICAO window rule the parser used.
 *
 * The reference year is the caller's `today`, never a clock read inside this function —
 * the same reason `:core` demands it (AGENTS.md §5).
 */
private fun String?.toIso(reference: IsoDate): IsoDate? {
    val raw = this ?: return null
    return CalendarDate.parseYymmdd(raw, reference.year)
}

private fun decodeBase64(value: String): ByteArray? {
    if (value.isBlank()) return null
    return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
}

private fun grayStr(value: Float): String = "%.3f".format(value)
