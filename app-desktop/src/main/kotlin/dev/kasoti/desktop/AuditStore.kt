package dev.kasoti.desktop

import dev.kasoti.audit.AuditChain
import dev.kasoti.audit.ChainVerification
import dev.kasoti.audit.DecisionRecord
import dev.kasoti.crypto.Hex
import dev.kasoti.crypto.constantTimeEquals
import dev.kasoti.platform.crypto.JcaDigest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The console's on-disk audit log (invariant I6, FR-S4).
 *
 * A hash chain is only useful if it survives the process that built it, so the records are
 * appended to a file — one `DecisionRecord` plus its chain hash per line — and the chain is
 * *rebuilt* on load rather than trusted. Rebuilding is the only honest option: a log that
 * asserts its own integrity is a log an attacker can edit along with everything else.
 *
 * Two attack shapes need more than a per-record hash, and both are handled here:
 *
 *  - **Editing a record.** Changing any field changes its hash, which no longer matches the
 *    stored one. Detected.
 *  - **Truncating the log.** A prefix of a valid chain is itself internally consistent, so
 *    per-record hashes say nothing. The last known tip is therefore written to its own file
 *    (`audit.log.tip`) and compared against the recomputed tip on load. Deleting the tip
 *    file is treated as tampering too, not as a fresh console.
 *
 * There is deliberately no update or delete path. The one-tap wipe is the only way records
 * leave, and it resets the tip alongside them.
 */
class AuditStore(private val logFile: Path) {

    private val digest = JcaDigest()
    private val chain = AuditChain(digest)

    init {
        load()
    }

    val size: Int get() = chain.size

    fun tip(): String = chain.tip()

    /** @return the hash of the record just appended. */
    fun append(record: DecisionRecord): String {
        val hash = chain.append(record)
        val line = encode(record) + "\t" + hash + "\n"
        logFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            logFile,
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
        writeTip(hash)
        return hash
    }

    fun all(): List<DecisionRecord> = chain.all()

    fun verify(): ChainVerification = chain.verify()

    /**
     * Delete every record and leave a marker.
     *
     * The marker is not decoration: "the wipe ran" and "the wipe never ran" must be
     * distinguishable after the fact, or an officer who was told the console was cleared
     * has to take it on trust.
     */
    fun wipe(at: String, removedRecords: Int): WipeReceipt {
        val tipBefore = chain.tip()
        Files.deleteIfExists(logFile)
        Files.deleteIfExists(tipFile)
        val receipt = WipeReceipt(atUtc = at, removedRecords = removedRecords, tipBefore = tipBefore)
        logFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            logFile,
            "# wiped at $at — $removedRecords record(s) removed\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        writeTip(GENESIS_TIP)
        return receipt
    }

    /** Human-readable dump for `audit.txt` in a case bundle and for `verify --verbose`. */
    fun excerpt(): String = buildString {
        append("# kasoti post-console audit chain\n")
        append("# genesis: kasoti-audit/1\n")
        chain.all().forEachIndexed { index, record ->
            append("# [$index] ${record.id} tip=${hashAt(index)}\n")
            append(record.canonical())
            append('\n')
        }
        val verification = chain.verify()
        append("# chain ${if (verification.valid) "VERIFIED" else "BROKEN"} length=${verification.length}\n")
    }

    private fun hashAt(index: Int): String =
        Hex.encode(digest.sha256((chain.all()[index].canonical() + "|" + previousTip(index)).toByteArray(Charsets.UTF_8)))

    private fun previousTip(index: Int): String =
        if (index == 0) GENESIS_TIP else hashAt(index - 1)

    private val tipFile: Path get() = logFile.resolveSibling(logFile.fileName.toString() + ".tip")

    private fun writeTip(hash: String) {
        logFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            tipFile,
            hash + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    /**
     * Rebuild the chain from the file, and refuse to go on if it does not add up.
     *
     * A line that cannot be decoded is skipped and reported rather than throwing — a
     * half-written final line after a power cut must not make the whole console refuse to
     * start, and silently "repairing" the chain would destroy the evidence the check exists
     * to protect. A line that *does* decode but does not hash to its stored value is
     * tampering, and stops everything.
     */
    private fun load() {
        if (!Files.exists(logFile)) return

        for (line in completeLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val tab = trimmed.lastIndexOf('\t')
            if (tab <= 0) continue
            val payload = trimmed.substring(0, tab)
            val stored = trimmed.substring(tab + 1)
            val record = decode(payload) ?: continue
            val recomputed = chain.append(record)
            if (!constantTimeEquals(
                    recomputed.toByteArray(StandardCharsets.US_ASCII),
                    stored.toByteArray(StandardCharsets.US_ASCII),
                )
            ) {
                throw ChainIntegrityException(
                    "audit record ${record.id} does not hash to its stored value; " +
                        "the log has been altered and no further records may be appended",
                )
            }
        }

        verifyTip()
    }

    /**
     * Only lines that were fully written.
     *
     * A torn final line is not newline-terminated, and a torn line is the *only* case where
     * the bytes on disk are genuinely ambiguous. Anything else missing its newline is
     * corruption we refuse rather than guess at.
     */
    private fun completeLines(): List<String> {
        val text = Files.readString(logFile, StandardCharsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        if (text.endsWith("\n")) return text.split("\n").dropLast(1)
        val lines = text.split("\n")
        return lines.dropLast(1)
    }

    /**
     * The stored tip is what detects truncation.
     *
     * A missing tip file beside a non-empty log is treated as tampering: an attacker who
     * can delete records can delete the tip too, and treating its absence as "fresh console"
     * would hand them a clean-slate verification for free.
     */
    private fun verifyTip() {
        if (chain.size == 0) {
            if (Files.exists(tipFile)) {
                Files.deleteIfExists(tipFile)
            }
            return
        }
        if (!Files.isRegularFile(tipFile)) {
            throw ChainIntegrityException(
                "the audit tip file is missing while ${chain.size} record(s) remain; " +
                    "the log cannot be shown to be unaltered",
            )
        }
        val stored = Files.readString(tipFile, StandardCharsets.UTF_8).trim()
        val recomputed = chain.tip()
        if (!constantTimeEquals(
                stored.toByteArray(StandardCharsets.US_ASCII),
                recomputed.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            throw ChainIntegrityException(
                "the recomputed chain tip does not match the stored tip; " +
                    "records have been added, removed or reordered since the last append",
            )
        }
    }

    /**
     * Canonical field encoding, one tab-separated field per record.
     *
     * Written by hand rather than through a serialisation library so that the file format
     * is a direct projection of `DecisionRecord.canonical()` — the exact bytes the chain
     * hashes. A round-trip through a JSON object model would let a field silently change
     * shape between what is hashed and what is stored, which is how a chain becomes
     * unverifiable in the field with no obvious cause.
     */
    private fun encode(record: DecisionRecord): String = listOf(
        record.id,
        record.timestampUtc,
        record.caseId,
        record.deviceId,
        record.verdict,
        record.findingCodes.joinToString("~"),
        record.thresholdVersion,
        record.fusionRuleVersion,
        record.embeddingModel.orEmpty(),
        record.overriddenBy.orEmpty(),
        record.overrideReasonCode.orEmpty(),
    ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    private fun decode(payload: String): DecisionRecord? {
        val fields = payload.split('\t')
        if (fields.size != FIELD_COUNT) return null
        return try {
            DecisionRecord(
                id = fields[0],
                timestampUtc = fields[1],
                caseId = fields[2],
                deviceId = fields[3],
                verdict = fields[4],
                findingCodes = fields[5].split('~').filter { it.isNotEmpty() },
                thresholdVersion = fields[6],
                fusionRuleVersion = fields[7],
                embeddingModel = fields[8].ifEmpty { null },
                overriddenBy = fields[9].ifEmpty { null },
                overrideReasonCode = fields[10].ifEmpty { null },
            )
        } catch (e: IllegalArgumentException) {
            // A record that violates DecisionRecord's own invariants (an override with no
            // reason code) is not loadable, and must not be loadable.
            null
        }
    }

    private companion object {
        const val FIELD_COUNT = 11
        val GENESIS_TIP: String = Hex.encode(
            JcaDigest().sha256("kasoti-audit/1".toByteArray(Charsets.UTF_8)),
        )
    }
}

/** Raised when the stored log does not hash to what it claims. Refuses all further appends. */
class ChainIntegrityException(message: String) : Exception(message)

/** What a one-tap wipe did, for the console to print and for a later auditor to find. */
data class WipeReceipt(val atUtc: String, val removedRecords: Int, val tipBefore: String)
