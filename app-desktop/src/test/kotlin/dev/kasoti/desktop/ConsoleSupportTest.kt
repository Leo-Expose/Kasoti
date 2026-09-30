package dev.kasoti.desktop

import dev.kasoti.audit.DecisionRecord
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CommandLineTest {

    @Test
    fun `a command with options parses`() {
        val line = CommandLine.parse(arrayOf("screen", "--image", "doc.png", "--track", "PASSPORT"))
        assertEquals("screen", line.command)
        assertEquals("doc.png", line.option("image"))
        assertEquals("PASSPORT", line.option("track"))
    }

    @Test
    fun `equals form parses`() {
        assertEquals("doc.png", CommandLine.parse(arrayOf("screen", "--image=doc.png")).option("image"))
    }

    @Test
    fun `a trailing option is a flag`() {
        val line = CommandLine.parse(arrayOf("screen", "--image", "doc.png", "--demo"))
        assertTrue(line.has("demo"))
        assertFalse(line.flag("verbose"))
    }

    @Test
    fun `negative flags override a default true`() {
        val line = CommandLine.parse(arrayOf("macro", "--no-clip"))
        assertFalse(line.flag("clip", default = true))
        assertFalse(line.flag("clip"))
    }

    @Test
    fun `paths are comma separated`() {
        val line = CommandLine.parse(arrayOf("screen", "--mrz", "LINE1,LINE2 , LINE3"))
        assertEquals(listOf("LINE1", "LINE2", "LINE3"), line.paths("mrz"))
    }

    @Test
    fun `an empty path list is empty not one empty string`() {
        assertEquals(emptyList(), CommandLine.parse(arrayOf("screen")).paths("mrz"))
    }

    @Test
    fun `no arguments is a usage error`() {
        assertFailsWith<UsageException> { CommandLine.parse(emptyArray()) }
    }

    @Test
    fun `a bare positional argument is a usage error`() {
        assertFailsWith<UsageException> { CommandLine.parse(arrayOf("screen", "doc.png")) }
    }

    @Test
    fun `an unknown option is rejected rather than ignored`() {
        val line = CommandLine.parse(arrayOf("screen", "--imag", "doc.png"))
        val failure = assertFailsWith<UsageException> { line.rejectUnknown(setOf("image")) }
        assertTrue(failure.message!!.contains("imag"))
    }

    @Test
    fun `a missing required option names itself`() {
        val failure = assertFailsWith<UsageException> { CommandLine.parse(arrayOf("screen")).required("image") }
        assertTrue(failure.message!!.contains("image"))
    }

    @Test
    fun `a non-numeric integer is a usage error not a zero`() {
        val line = CommandLine.parse(arrayOf("screen", "--grey-streak", "many"))
        assertFailsWith<UsageException> { line.int("grey-streak", 0) }
    }

    @Test
    fun `an invalid boolean is a usage error not a silent false`() {
        assertFailsWith<UsageException> { CommandLine.parse(arrayOf("screen", "--demo=maybe")).flag("demo") }
    }
}

class IdsTest {

    @Test
    fun `a case id has the documented shape`() {
        val id = Ids.caseId(1_757_000_000_000)
        assertTrue(id.startsWith("case_"))
        assertEquals(Ids.ULID_LENGTH + 5, id.length)
    }

    @Test
    fun `an event id has the documented shape`() {
        assertTrue(Ids.eventId(1_757_000_000_000).startsWith("evt_"))
    }

    @Test
    fun `the ulid alphabet excludes the confusable characters`() {
        repeat(200) { index ->
            val id = Ids.ulid(1_757_000_000_000L + index)
            assertFalse(id.contains('I'), "I is confusable with 1")
            assertFalse(id.contains('L'), "L is confusable with 1")
            assertFalse(id.contains('O'), "O is confusable with 0")
            assertFalse(id.contains('U'), "U is confusable with V")
        }
    }

    /** Two post machines starting at the same millisecond must not collide. */
    @Test
    fun `ids generated in the same millisecond are distinct`() {
        val ids = (1..500).map { Ids.ulid(1_757_000_000_000) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the timestamp prefix is monotonic in the supplied clock`() {
        assertTrue(Ids.ulid(1_000L) < Ids.ulid(2_000L))
    }
}

class AuditStoreTest {

    private fun record(
        id: String = "rec_1",
        caseId: String = "case_1",
        verdict: String = "GREEN",
        overrideBy: String? = null,
        reason: String? = null,
    ) = DecisionRecord(
        id = id,
        timestampUtc = "2026-09-29T10:00:00.000Z",
        caseId = caseId,
        deviceId = "post3-ph1",
        verdict = verdict,
        findingCodes = listOf("M_OK"),
        thresholdVersion = "v1",
        fusionRuleVersion = "console-local-2026.09-fusion-v4",
        embeddingModel = null,
        overriddenBy = overrideBy,
        overrideReasonCode = reason,
    )

    private fun withStore(block: (Path, AuditStore) -> Unit) {
        val dir = createTempDirectory("kasoti-audit")
        try {
            val file = dir.resolve("nested").resolve("audit.log")
            Files.createDirectories(file.parent)
            block(file, AuditStore(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a fresh store is empty and verifies`() {
        withStore { _, store ->
            assertEquals(0, store.size)
            assertTrue(store.verify().valid)
        }
    }

    @Test
    fun `records round-trip through the file and re-verify`() {
        withStore { file, _ ->
            val first = AuditStore(file)
            first.append(record("rec_1"))
            first.append(record("rec_2", verdict = "RED"))

            val reopened = AuditStore(file)
            assertEquals(2, reopened.size)
            assertTrue(reopened.verify().valid)
            assertEquals("RED", reopened.all().last().verdict)
        }
    }

    @Test
    fun `an override survives the round trip with its reason code`() {
        withStore { file, _ ->
            val first = AuditStore(file)
            first.append(record("rec_1"))
            first.append(record("rec_2", verdict = "GREEN", overrideBy = "sup-1", reason = "SUP_DOC_AUTHENTIC"))

            val reopened = AuditStore(file).all().last()
            assertEquals("sup-1", reopened.overriddenBy)
            assertEquals("SUP_DOC_AUTHENTIC", reopened.overrideReasonCode)
        }
    }

    @Test
    fun `a finding list survives the round trip`() {
        withStore { file, _ ->
            val first = AuditStore(file)
            first.append(
                record("rec_1").copy(findingCodes = listOf("R_MATH_01", "R_QR_02", "A_FACE_01")),
            )
            assertEquals(
                listOf("R_MATH_01", "R_QR_02", "A_FACE_01"),
                AuditStore(file).all().single().findingCodes,
            )
        }
    }

    /** The tamper case: an edited verdict must break the chain, not be silently accepted. */
    @Test
    fun `an edited record is refused on reload`() {
        withStore { file, _ ->
            val first = AuditStore(file)
            first.append(record("rec_1"))
            first.append(record("rec_2", verdict = "RED"))

            val text = Files.readString(file)
            Files.writeString(file, text.replace("RED", "GREEN"))

            val failure = assertFailsWith<ChainIntegrityException> { AuditStore(file) }
            assertTrue(failure.message!!.contains("rec_2"))
        }
    }

    @Test
    fun `a deleted record is refused on reload`() {
        withStore { file, _ ->
            val first = AuditStore(file)
            first.append(record("rec_1"))
            first.append(record("rec_2"))
            first.append(record("rec_3"))

            val lines = Files.readAllLines(file)
            Files.writeString(file, lines.subList(0, 1).joinToString("\n") + "\n")

            assertFailsWith<ChainIntegrityException> { AuditStore(file) }
        }
    }

    @Test
    fun `a record whose stored hash was replaced is refused`() {
        withStore { file, _ ->
            AuditStore(file).append(record("rec_1"))
            val line = Files.readString(file).trim()
            val hash = line.substringAfterLast('\t')
            Files.writeString(file, line.substringBeforeLast('\t') + "\t" + "0".repeat(hash.length) + "\n")
            assertFailsWith<ChainIntegrityException> { AuditStore(file) }
        }
    }

    @Test
    fun `a wipe removes every record and leaves a marker`() {
        withStore { file, store ->
            store.append(record("rec_1"))
            store.append(record("rec_2"))
            val receipt = store.wipe("2026-09-29T11:00:00.000Z", removedRecords = 2)
            assertEquals(2, receipt.removedRecords)
            assertTrue(Files.readString(file).contains("wiped at"))
            assertEquals(0, AuditStore(file).size)
        }
    }

    @Test
    fun `a half-written final line does not stop the console starting`() {
        withStore { file, _ ->
            AuditStore(file).append(record("rec_1"))
            Files.writeString(
                file,
                "rec_2\tpartial",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND,
            )
            assertEquals(1, AuditStore(file).size)
        }
    }

    /**
     * A record that violates DecisionRecord's own invariants must not be loadable.
     *
     * An override with an operator but no reason code is exactly what `DecisionRecord`'s
     * constructor refuses, so writing one by hand must not become a way to smuggle an
     * unattributable decision into the chain.
     */
    @Test
    fun `a record with an override and no reason code is skipped`() {
        withStore { file, _ ->
            AuditStore(file).append(record("rec_1"))
            val fields = Files.readString(file).trim().substringBeforeLast('\t').split('\t').toMutableList()
            fields[0] = "rec_x"
            fields[9] = "sup-1" // overriddenBy
            fields[10] = "" // overrideReasonCode — deliberately empty
            Files.writeString(
                file,
                fields.joinToString("\t") + "\t" + "0".repeat(64) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND,
            )
            assertEquals(1, AuditStore(file).size, "the invalid record should be skipped, not loaded")
        }
    }

    /**
     * Truncating the log is tampering, and only the stored tip can say so.
     *
     * A prefix of a valid chain hashes perfectly: every record still follows from its
     * predecessor. Without the tip, an attacker who deletes the last ten decisions leaves a
     * chain that verifies, which is the whole attack.
     */
    @Test
    fun `a truncated log is refused on reload`() {
        withStore { file, _ ->
            val store = AuditStore(file)
            store.append(record("rec_1"))
            store.append(record("rec_2"))
            store.append(record("rec_3"))

            val lines = Files.readAllLines(file)
            Files.writeString(file, lines.subList(0, 2).joinToString("\n") + "\n")

            val failure = assertFailsWith<ChainIntegrityException> { AuditStore(file) }
            assertTrue(failure.message!!.contains("tip"), failure.message)
        }
    }

    @Test
    fun `a deleted tip file is refused while records remain`() {
        withStore { file, _ ->
            AuditStore(file).append(record("rec_1"))
            Files.deleteIfExists(file.resolveSibling(file.fileName.toString() + ".tip"))
            assertFailsWith<ChainIntegrityException> { AuditStore(file) }
        }
    }

    @Test
    fun `the excerpt names the chain length and the verification result`() {
        withStore { _, store ->
            store.append(record("rec_1"))
            val excerpt = store.excerpt()
            assertTrue(excerpt.contains("VERIFIED"))
            assertTrue(excerpt.contains("length=1"))
        }
    }

    @Test
    fun `two stores on the same file agree on the tip`() {
        withStore { file, _ ->
            val a = AuditStore(file)
            a.append(record("rec_1"))
            assertEquals(a.tip(), AuditStore(file).tip())
            assertNotEquals(a.tip(), "genesis")
        }
    }
}

class SupervisorPinTest {

    @Test
    fun `a correct pin matches`() {
        assertTrue(SupervisorPin.matches("hunter2hunter2", "hunter2hunter2"))
    }

    @Test
    fun `a stored sha256 digest matches its pin`() {
        val stored = SupervisorPin.PREFIX + SupervisorPin.digestOf("correct-horse-battery")
        assertTrue(SupervisorPin.matches("correct-horse-battery", stored))
    }

    @Test
    fun `a wrong pin does not match`() {
        assertFalse(SupervisorPin.matches("hunter2hunter3", "hunter2hunter2"))
    }

    @Test
    fun `a missing stored pin never matches`() {
        assertFalse(SupervisorPin.matches("hunter2hunter2", null))
    }

    /** A short pin on a shared laptop is not a control. */
    @Test
    fun `a short supplied pin is refused even if it matches`() {
        assertFalse(SupervisorPin.matches("1234", "1234"))
    }

    @Test
    fun `a short stored pin cannot be matched by a long guess`() {
        assertFalse(SupervisorPin.matches("123456789", "1234"))
    }

    @Test
    fun `the digest is the standard SHA-256 of the UTF-8 pin`() {
        val expected = dev.kasoti.crypto.Hex.encode(
            dev.kasoti.platform.crypto.JcaDigest().sha256("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(expected, SupervisorPin.digestOf("abc"))
    }

    @Test
    fun `a malformed stored digest does not match`() {
        assertFalse(SupervisorPin.matches("hunter2hunter2", SupervisorPin.PREFIX + "not-hex"))
    }
}
