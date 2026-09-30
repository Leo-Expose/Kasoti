package dev.kasoti.desktop.bundle

import dev.kasoti.audit.DecisionRecord
import dev.kasoti.desktop.ConsolePolicy
import dev.kasoti.factory.GrayImage
import dev.kasoti.fusion.Evidence
import dev.kasoti.fusion.Finding
import dev.kasoti.fusion.FindingCode
import dev.kasoti.fusion.Severity
import dev.kasoti.fusion.Track
import dev.kasoti.fusion.Verdict
import dev.kasoti.mrz.MrzBuilder
import dev.kasoti.mrz.MrzPerson
import dev.kasoti.mrz.MrzParser
import dev.kasoti.time.IsoDate
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The case bundle (DESIGN.md §4, NFR-P2).
 *
 * Every assertion here is about what is *absent*. A bundle that contains everything is
 * trivially easy to write and useless; the whole design is a subtraction, and a subtraction
 * has no test unless somebody deliberately puts the PII in and checks it does not come out
 * the other side.
 */
class CaseBundleWriterTest {

    private fun decision() = dev.kasoti.desktop.screen.Decision(
        verdict = Verdict.AMBER,
        findings = listOf(
            Finding(FindingCode.A_FACE_01, Severity.AMBER, "face.similarity", "0.480 is in the ambiguous band"),
            Finding(FindingCode.MACRO_OK, Severity.INFO, "macro.zones", "zones agree"),
        ),
    )

    private val mrzResult = MrzParser.parse(
        MrzBuilder.buildTd3(
            MrzPerson(
                surname = "SHARMA",
                givenNames = "RAMESH",
                documentNumber = "AB1234567",
                birthDate = IsoDate(1990, 6, 8),
                expiryDate = IsoDate(2031, 6, 7),
            ),
            referenceYear = 2026,
        ),
        referenceYear = 2026,
    )

    private fun result(demoMode: Boolean = false) = dev.kasoti.desktop.screen.ScreeningResult(
        caseId = "case_TESTCASE0000000000000000AA",
        track = Track.PASSPORT,
        image = Path.of("doc.png"),
        evidence = Evidence(track = Track.PASSPORT, demoMode = demoMode),
        decision = decision(),
        thresholdVersion = "v1",
        fusionRuleVersion = "console-local-2026.09-fusion-v4",
        layers = listOf(
            dev.kasoti.desktop.screen.LayerStatus("math", dev.kasoti.desktop.screen.LayerStatus.State.RAN, "TD3"),
            dev.kasoti.desktop.screen.LayerStatus(
                "face",
                dev.kasoti.desktop.screen.LayerStatus.State.UNAVAILABLE,
                "no interpreter",
            ),
        ),
        mrz = mrzResult,
        warnings = emptyList(),
    )

    private fun record() = DecisionRecord(
        id = "rec_TEST0000000000000000000AA",
        timestampUtc = "2026-09-29T10:00:00.000Z",
        caseId = "case_TESTCASE0000000000000000AA",
        deviceId = "post3-ph1",
        verdict = "AMBER",
        findingCodes = listOf("A_FACE_01", "MACRO_OK"),
        thresholdVersion = "v1",
        fusionRuleVersion = "console-local-2026.09-fusion-v4",
        embeddingModel = null,
    )

    private fun withDir(block: (Path) -> Unit) {
        val dir = createTempDirectory("kasoti-bundle")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun patch(size: Int = 64): GrayImage =
        GrayImage.fromBytes(size, size, ByteArray(size * size) { (it % 251).toByte() })

    @Test
    fun `a bundle contains exactly the three documented entries`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        val receipt = CaseBundleWriter().write(
            result = result(),
            record = record(),
            auditExcerpt = "# chain\n",
            target = target,
        )
        assertEquals(listOf("case.json", "audit.txt"), receipt.entries)
        assertTrue(Files.exists(target))
        assertFalse(Files.exists(dir.resolve("case.zip.partial")), "the staging file must not survive")
    }

    @Test
    fun `a bundle is far under the 500 KB budget`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        val receipt = CaseBundleWriter().write(result(), record(), "# chain\n", target)
        assertTrue(receipt.sizeBytes < ConsolePolicy.BUNDLE_SIZE_BUDGET_BYTES)
    }

    @Test
    fun `macro crops are included when supplied`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        val receipt = CaseBundleWriter().write(
            result = result(),
            record = record(),
            auditExcerpt = "# chain\n",
            target = target,
            crops = mapOf("photoZone" to patch(), "textZone" to patch()),
        )
        assertContains(receipt.entries, "crops/photoZone.png")
        assertContains(receipt.entries, "crops/textZone.png")
        ZipFile(target.toFile()).use { zip ->
            val bytes = zip.getInputStream(zip.getEntry("crops/photoZone.png")).use { it.readBytes() }
            val decoded = javax.imageio.ImageIO.read(bytes.inputStream())
            assertEquals(64, decoded.width)
            assertEquals(64, decoded.height)
        }
    }

    /** NFR-P2: raw live-face data is never in a bundle unless someone says so explicitly. */
    @Test
    fun `live face data is excluded by default`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        val receipt = CaseBundleWriter().write(result(), record(), "# chain\n", target)
        assertFalse(receipt.containsLiveFace)
        ZipFile(target.toFile()).use { zip ->
            assertFalse(zip.entries().toList().any { it.name.contains("LIVE_FACE") })
        }
    }

    @Test
    fun `live face data is included, named and flagged when explicitly requested`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        val receipt = CaseBundleWriter().write(
            result = result(),
            record = record(),
            auditExcerpt = "# chain\n",
            target = target,
            includeLiveFace = ByteArray(512) { it.toByte() },
        )
        assertTrue(receipt.containsLiveFace)
        ZipFile(target.toFile()).use { zip ->
            assertContains(zip.entries().toList().map { it.name }, "crops/LIVE_FACE_RESTRICTED.bin")
            val caseJson = String(zip.getInputStream(zip.getEntry("case.json")).readBytes(), Charsets.UTF_8)
            assertContains(caseJson, "\"liveFaceIncluded\":true")
        }
    }

    /** The subtraction that matters: the holder's identity must not survive. */
    @Test
    fun `no PII reaches the bundle even though the MRZ carried it`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        CaseBundleWriter().write(result(), record(), "# chain\n", target, crops = mapOf("photoZone" to patch()))
        ZipFile(target.toFile()).use { zip ->
            val everything = zip.entries().toList().joinToString("\n") { entry ->
                String(zip.getInputStream(entry).readBytes(), Charsets.UTF_8)
            }
            for (secret in listOf("SHARMA", "RAMESH", "AB1234567", "1990-06-08", "080690", "6908061")) {
                assertFalse(everything.contains(secret), "'$secret' reached the bundle")
            }
        }
    }

    @Test
    fun `the MRZ check results are kept even though the characters are not`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        CaseBundleWriter().write(result(), record(), "# chain\n", target)
        ZipFile(target.toFile()).use { zip ->
            val caseJson = String(zip.getInputStream(zip.getEntry("case.json")).readBytes(), Charsets.UTF_8)
            assertContains(caseJson, "DOCUMENT_NUMBER")
            assertContains(caseJson, "\"ok\":true")
        }
    }

    @Test
    fun `both policy versions and the finding vocabulary travel`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        CaseBundleWriter().write(result(), record(), "# chain\n", target)
        ZipFile(target.toFile()).use { zip ->
            val caseJson = String(zip.getInputStream(zip.getEntry("case.json")).readBytes(), Charsets.UTF_8)
            assertContains(caseJson, "\"thresholdVersion\":\"v1\"")
            assertContains(caseJson, "console-local-2026.09-fusion-v4")
            assertContains(caseJson, "A_FACE_01")
            // Both languages, so an officer who cannot read the console's English can
            // still act on the bundle.
            assertContains(caseJson, "messageHi")
            assertContains(caseJson, "messageEn")
        }
    }

    @Test
    fun `a demo run is marked as one in the bundle`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        CaseBundleWriter().write(result(demoMode = true), record(), "# chain\n", target)
        ZipFile(target.toFile()).use { zip ->
            assertContains(
                String(zip.getInputStream(zip.getEntry("case.json")).readBytes(), Charsets.UTF_8),
                "\"demoMode\":true",
            )
        }
    }

    @Test
    fun `an existing bundle is replaced rather than appended to`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        val writer = CaseBundleWriter()
        writer.write(result(), record(), "# chain\n", target)
        writer.write(result(), record(), "# chain\n", target)
        ZipFile(target.toFile()).use { zip ->
            assertEquals(2, zip.entries().toList().size)
        }
    }

    @Test
    fun `the audit excerpt is the chain, not a summary`() = withDir { dir ->
        val target = dir.resolve("case.zip")
        CaseBundleWriter().write(result(), record(), "# kasoti post-console audit chain\n", target)
        ZipFile(target.toFile()).use { zip ->
            assertContains(
                String(zip.getInputStream(zip.getEntry("audit.txt")).readBytes(), Charsets.UTF_8),
                "kasoti post-console audit chain",
            )
        }
    }

    @Test
    fun `a bundle that cannot fit the budget is refused and nothing is left behind`() = withDir { dir ->
        // 8 MB of incompressible noise: a real bundle of that size means the crops are
        // the problem, and the right answer is to refuse, not to ship a 16 MB zip.
        val noise = java.util.Random(7).let { rnd ->
            ByteArray(8 * 1024 * 1024).also { rnd.nextBytes(it) }
        }
        val target = dir.resolve("case.zip")
        val failure = assertFailsWith<BundleTooLargeException> {
            CaseBundleWriter().write(result(), record(), "# chain\n", target, includeLiveFace = noise)
        }
        assertTrue(failure.sizeBytes > failure.budgetBytes)
        assertFalse(Files.exists(target), "an over-budget bundle must not be written")
        assertFalse(Files.exists(dir.resolve("case.zip.partial")), "the staging file must be cleaned up")
    }
}
