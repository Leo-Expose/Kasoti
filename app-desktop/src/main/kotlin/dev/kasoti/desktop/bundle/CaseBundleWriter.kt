package dev.kasoti.desktop.bundle

import dev.kasoti.audit.DecisionRecord
import dev.kasoti.desktop.ConsolePolicy
import dev.kasoti.desktop.screen.ScreeningResult
import dev.kasoti.factory.GrayImage
import dev.kasoti.i18n.Language
import dev.kasoti.i18n.Messages
import dev.kasoti.platform.imaging.ImageIoImaging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The case bundle (DESIGN.md §4, NFR-P2).
 *
 * `case.json` + `crops/` + `audit.txt`, under 500 KB. The interesting part is what is
 * *absent*.
 *
 * A bundle is the artefact that crosses the consent boundary — DATA.md §1 is explicit that
 * no real-PII pixels may — and a bundle is also the thing an investigator reads six months
 * later. Those two facts pull in opposite directions, and the resolution used here is:
 * keep everything that is *about the decision* and drop everything that is *about the
 * person*. Findings, severities, evidence references, threshold version, fusion rule
 * version, chain hashes, macro crops. Not names, not dates of birth, not MRZ text, not the
 * live face crop.
 *
 * In particular the MRZ *check results* travel but the MRZ *characters* do not. The
 * reviewer needs to see which field failed; they do not need the holder's name in a zip
 * that gets emailed.
 */
class CaseBundleWriter(private val imaging: ImageIoImaging = ImageIoImaging()) {

    /**
     * @param includeLiveFace NFR-P2: off by default. Live face data is a biometric
     *   template source and must never leave the device without an explicit, logged
     *   decision by a supervisor.
     * @param crops macro crops keyed by name; the caller decides which zones are in scope.
     */
    fun write(
        result: ScreeningResult,
        record: DecisionRecord,
        auditExcerpt: String,
        target: Path,
        crops: Map<String, GrayImage> = emptyMap(),
        includeLiveFace: ByteArray? = null,
    ): BundleReceipt {
        target.parent?.let { Files.createDirectories(it) }
        val staging = target.resolveSibling(target.fileName.toString() + ".partial")
        Files.deleteIfExists(staging)

        var faceIncluded = false
        ZipOutputStream(Files.newOutputStream(staging)).use { zip ->
            zip.put("case.json", caseJson(result, record, includeLiveFace != null))
            for ((name, patch) in crops) {
                zip.put("crops/$name.png", imaging.encodeGrayPng(patch))
            }
            if (includeLiveFace != null) {
                // A named, obvious entry. Anyone opening the bundle can see immediately
                // that it contains biometric data, which is the point of NFR-P2.
                zip.put("crops/LIVE_FACE_RESTRICTED.bin", includeLiveFace)
                faceIncluded = true
            }
            zip.put("audit.txt", auditExcerpt.toByteArray(StandardCharsets.UTF_8))
        }

        val size = Files.size(staging)
        if (size > ConsolePolicy.BUNDLE_SIZE_BUDGET_BYTES) {
            Files.deleteIfExists(staging)
            throw BundleTooLargeException(size, ConsolePolicy.BUNDLE_SIZE_BUDGET_BYTES)
        }
        Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return BundleReceipt(
            path = target,
            sizeBytes = size,
            entries = buildList {
                add("case.json")
                add("audit.txt")
                crops.keys.forEach { add("crops/$it.png") }
                if (faceIncluded) add("crops/LIVE_FACE_RESTRICTED.bin")
            },
            containsLiveFace = faceIncluded,
        )
    }

    /**
     * The decision, in full, with no personal data.
     *
     * Written with the explicit builder rather than by reflecting over `Evidence` so that
     * adding a PII-bearing field to `:core` cannot silently start shipping it. A field
     * that is not named here is not in the bundle — which is the safe default, and the
     * only one that survives the next schema change.
     */
    private fun caseJson(result: ScreeningResult, record: DecisionRecord, faceIncluded: Boolean): ByteArray {
        val findings = JsonArray(
            result.decision.findings.map { finding ->
                buildJsonObject {
                    put("code", finding.code.name)
                    put("severity", finding.severity.name)
                    put("evidenceRef", finding.evidenceRef)
                    put("messageEn", Messages.of(finding.code, Language.ENGLISH))
                    put("messageHi", Messages.of(finding.code, Language.HINDI))
                    put("detail", finding.message)
                }
            },
        )
        val document = buildJsonObject {
            put("schema", "kasoti.case/1")
            put("caseId", record.caseId)
            put("createdUtc", record.timestampUtc)
            put("deviceId", record.deviceId)
            put("track", result.track.name)
            put("verdict", result.decision.verdict.name)
            put("requiresSecondary", result.decision.verdict.requiresSecondary)
            put("thresholdVersion", record.thresholdVersion)
            put("thresholdRunId", result.thresholdVersion)
            put("fusionRuleVersion", record.fusionRuleVersion)
            put("embeddingModel", record.embeddingModel)
            put("demoMode", result.evidence.demoMode)
            put("piiIncluded", false)
            put("liveFaceIncluded", faceIncluded)
            put("findings", findings)
            put("mrzChecks", mrzChecks(result))
            put("layers", JsonArray(result.layers.map { JsonPrimitive("${it.name}:${it.state}") }))
        }
        return document.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Which MRZ check digits passed, and nothing about what they cover.
     *
     * The field *name* and the boolean are the decision-relevant part. The values would
     * be the holder's name and date of birth, and those do not belong in a file whose
     * whole purpose is to be handed to somebody who did not screen the person.
     */
    private fun mrzChecks(result: ScreeningResult): JsonArray {
        val mrz = result.mrz ?: return JsonArray(emptyList())
        return JsonArray(
            mrz.checks.map { check ->
                buildJsonObject {
                    put("field", check.field.name)
                    put("ok", check.ok)
                }
            },
        )
    }

    /**
     * Write one entry.
     *
     * `closeEntry()` rather than `putNextEntry(null)`: the null form was a JDK-internal
     * idiom and it dereferences a field the platform has since changed, so it throws an NPE
     * on a current runtime.
     */
    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}

/** A bundle that exceeded the 500 KB budget and was not written (NFR-P2). */
class BundleTooLargeException(val sizeBytes: Long, val budgetBytes: Long) :
    Exception("case bundle is $sizeBytes bytes, over the ${budgetBytes}-byte budget; the crops are too large")

/** What was written, for the console and for the test that checks the contents. */
data class BundleReceipt(
    val path: Path,
    val sizeBytes: Long,
    val entries: List<String>,
    val containsLiveFace: Boolean,
)
