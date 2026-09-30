package dev.kasoti.android.field

import dev.kasoti.crypto.SignatureVerifier
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.QrEvidence
import dev.kasoti.qr.KeyRing
import dev.kasoti.qr.QrCrossCheck
import dev.kasoti.qr.QrMismatch
import dev.kasoti.qr.QrPayload
import dev.kasoti.qr.SecureQr
import dev.kasoti.qr.SigResult
import dev.kasoti.qr.UnsignedQr

/**
 * The QR layer (FR-Q1 signed, FR-Q2 unsigned).
 *
 * ## The one rule this class exists to enforce
 *
 * **An unsigned QR is not evidence.** A forger can print any unsigned QR they like, so an
 * unsigned payload may only ever be *cross-checked* against what is printed (A-QR-01), never
 * trusted. Everything else in the QR path — signature verification, the mismatch list, the
 * `keysStale` flag — is available only to a payload that carries a signature.
 *
 * That is why [evaluate] takes the `signed` bit from the payload itself rather than from a
 * caller-supplied boolean: there is no way to reach the verifying branch with an unsigned
 * payload, and no flag to flip that would change that.
 */
class QrLayer(private val verifier: SignatureVerifier) {

    data class Outcome(
        val evidence: QrEvidence?,
        /** The verification result, for the case bundle. `null` when no QR was presented. */
        val signature: SigResult?,
        val keyId: String?,
        /** A test-ring verification is a *simulated* result and must be labelled as one. */
        val simulated: Boolean,
        val unsignedFields: Map<String, String>,
    ) {
        val present: Boolean get() = evidence?.present == true
    }

    /**
     * @param payload the scanned payload, or `null` when no QR was found.
     * @param ring the bundled public keys. An empty ring degrades to "cannot verify", never to
     *   "verified" — a missing key file must not be a pass.
     */
    fun evaluate(payload: QrPayload?, ring: KeyRing, printName: String, printDob: String): Outcome {
        if (payload == null) {
            return Outcome(
                evidence = QrEvidence(present = false, signed = false, signatureValid = false),
                signature = null,
                keyId = null,
                simulated = false,
                unsignedFields = emptyMap(),
            )
        }

        if (!payload.signed) {
            // FR-Q2: parse, contribute consistency only, and say UNSIGNED in the evidence.
            val fields = UnsignedQr.parse(String(payload.raw, Charsets.ISO_8859_1))
            val mismatches = consistencyMismatches(fields, printName, printDob)
            return Outcome(
                evidence = QrEvidence(
                    present = true,
                    signed = false,
                    signatureValid = false,
                    mismatches = mismatches,
                    unsignedFieldsPresent = fields.isNotEmpty(),
                ),
                signature = null,
                keyId = null,
                simulated = false,
                unsignedFields = fields,
            )
        }

        val result = SecureQr.verify(payload, ring, verifier)
        val keyId = when (result) {
            is SigResult.Valid -> result.keyId
            is SigResult.StaleKeys -> result.keyId
            SigResult.Invalid, SigResult.UnknownKey -> null
        }
        val valid = result is SigResult.Valid
        val mismatches = if (valid) crossCheck(payload, printName, printDob) else emptyList()

        return Outcome(
            evidence = QrEvidence(
                present = true,
                signed = true,
                signatureValid = valid,
                // `StaleKeys` verifies but is past rotation: recorded, and `:core` attaches
                // `SYS_KEYS_STALE` so the operator sees the keyring needs attention.
                keysStale = result is SigResult.StaleKeys,
                mismatches = mismatches,
                unsignedFieldsPresent = false,
            ),
            signature = result,
            keyId = keyId,
            simulated = valid && keyId?.startsWith(TEST_KEY_PREFIX) == true,
            unsignedFields = emptyMap(),
        )
    }

    /** Field-level disagreement between a *signed* QR and the print (R-QR-02). */
    private fun crossCheck(payload: QrPayload, printName: String, printDob: String): List<Pair<String, Pair<String, String>>> =
        QrCrossCheck.compare(payload, printName, printDob).map { it.toPair() }

    /**
     * Consistency-only comparison for an unsigned payload (A-QR-01).
     *
     * Same field set, different weight: this produces an AMBER, never a RED, because a forger
     * chose these values. The mismatch list is attached so the supervisor can see them side by
     * side, which is the only thing an unsigned QR is good for.
     */
    private fun consistencyMismatches(
        fields: Map<String, String>,
        printName: String,
        printDob: String,
    ): List<Pair<String, Pair<String, String>>> {
        val out = mutableListOf<Pair<String, Pair<String, String>>>()
        fields["name"]?.let { qr ->
            if (printName.isNotBlank() && dev.kasoti.checks.VizMrzMatch.nameScore(printName, qr) < 1f) {
                out += "name" to (qr to printName)
            }
        }
        fields["dob"]?.let { qr ->
            if (printDob.isNotBlank() && qr != printDob) out += "dob" to (qr to printDob)
        }
        return out
    }

    /**
     * Load a key ring from a bundled asset.
     *
     * The provenance review is a human step (BUILD.md §6) and is *not* done here, so this ships
     * an empty ring by default: a missing or unproven key file degrades to "cannot verify",
     * which `:core` reads as an unresolved signed-QR layer, and for Aadhaar — where SIGNED_QR is
     * load-bearing — that makes GREEN unreachable. A card that genuinely cannot be verified is
     * a secondary inspection. A card verified against an invented key is a security incident.
     */
    fun ringFrom(assetBytes: ByteArray?): KeyRing {
        if (assetBytes == null || assetBytes.isEmpty()) return KeyRing.empty()
        // Parsed by the provisioning step; the shape is validated there. Returning empty on
        // anything unrecognised keeps the fail-closed default honest.
        return KeyRing.empty()
    }

    companion object {
        /** Key ids with this prefix are test-ring material and their verdicts are SIMULATED. */
        const val TEST_KEY_PREFIX = "uidai_test"
    }
}

private fun QrMismatch.toPair(): Pair<String, Pair<String, String>> =
    field to (qrValue to printValue)

/** The codes a QR outcome can contribute, for a caller that wants them without fusion. */
fun QrLayer.Outcome.codes(): List<FindingCode> = when {
    !present -> emptyList()
    evidence?.signed == true && evidence.signatureValid -> listOf(FindingCode.Q_SIG_OK)
    evidence?.signed == true -> listOf(FindingCode.R_QR_01)
    evidence?.mismatches?.isNotEmpty() == true -> listOf(FindingCode.A_QR_01)
    else -> listOf(FindingCode.A_QR_01)
}
