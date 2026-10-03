package dev.kasoti.desktop

import dev.kasoti.desktop.screen.ConsoleView
import dev.kasoti.desktop.screen.ModelLocator
import dev.kasoti.desktop.screen.SvmModelException
import dev.kasoti.fusion.FindingCode
import dev.kasoti.mrz.MrzBuilder
import dev.kasoti.mrz.MrzPerson
import dev.kasoti.time.IsoDate
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One console, one home directory, and the transcript it printed.
 *
 * The transcript is captured rather than printed so the tests can assert on exactly what an
 * operator would have seen — which is the actual contract here. A console that computes the
 * right verdict and prints the wrong thing has still failed.
 */
internal class Fixture(
    val dir: Path,
    /**
     * How the macro model is found. Defaults to a locator that can find nothing, so the
     * missing-model path is exercised by every test in this file unless it asks otherwise —
     * a test that meant to assert on the MRZ must not be silently asserting on a model's
     * opinion as well.
     */
    locator: ModelLocator = ModelLocator(env = emptyMap(), installRoot = null, workingDir = dir),
) {
    val home = ConsoleHome(dir)
    val transcript = mutableListOf<String>()
    val console = Console(home, view = ConsoleView { transcript += it }, modelLocator = locator)

    val text: String get() = transcript.joinToString("\n")

    /**
     * The macro fixture, written once per test.
     *
     * A confident, always-agreeing classifier so the macro layer produces a clean MATCH.
     * Without it the document-level macro reading is noise, and a test about an MRZ check
     * digit ends up asserting on a paste-attack finding instead.
     */
    fun svmFile(): Path = dir.resolve("fixture-svm.json").also {
        Files.writeString(it, SvmModelFileFixture.confidentAgreeingModelJson())
    }

    /**
     * Screen the synthetic document and return the case id the console reported.
     *
     * `--fusion local` on purpose. These tests are about the *console* — the cascade's
     * plumbing, the audit chain, the bundle, the PIN — and they need a verdict they can
     * pin. `:core`'s `FusionEngine` is fail-closed on unresolved load-bearing layers, so on
     * a file import with no face and no diary it correctly returns GREY; asserting around
     * that would be re-testing `:core`'s rules from the wrong side of a module boundary.
     * `CoreFusionEngineTest` covers the delegation itself.
     */
    fun screenOnce(vararg extra: String): String {
        val image = dir.resolve("doc-${counter++}.png")
        syntheticDocument(image)
        val fields = dir.resolve("fields-${counter++}.json")
        Files.writeString(fields, sidecarJson(validMrz()))
        val argv = listOf(
            "screen", "--image", image.toString(), "--track", "PASSPORT",
            "--fields", fields.toString(), "--today", TODAY,
            "--fusion", "local", "--svm", svmFile().toString(),
        ) + extra
        val code = console.run(CommandLine.parse(argv.toTypedArray()))
        assertEquals(ConsolePolicy.Exit.OK, code.exitCode, "screening failed: ${code.summary}")
        return caseId()
    }

    /**
     * The most recently written case id, read from the case store.
     *
     * Scraping it out of the printed transcript would be fragile in a way that hides real
     * regressions: the table prints both a `case` header and a `case id` line, and a
     * substring rule that picks the wrong one produces a plausible-looking id for a case
     * that was never recorded. "Most recent" rather than "the only one" so a test can screen
     * twice and still be talking about the case it just made.
     */
    fun caseId(): String = Files.list(home.casesDir).use { stream ->
        stream.sorted { a, b -> Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)) }
            .toList()
            .last()
            .fileName
            .toString()
    }

    fun run(vararg args: String): CommandResult = console.run(CommandLine.parse(args.asList().toTypedArray()))

    /** A `screen` invocation pinned to the local engine and the agreeing macro fixture. */
    fun localScreen(image: Path, fields: Path, track: String = "PASSPORT"): Array<String> = arrayOf(
        "screen", "--image", image.toString(), "--fields", fields.toString(),
        "--today", TODAY, "--track", track, "--fusion", "local", "--svm", svmFile().toString(),
    )

    fun summaryOfFirstCase(): String = Files.readString(home.caseDir(caseId()).resolve("summary.txt"))

    /**
     * The finding codes recorded in the case summary.
     *
     * Parsed from the `findings:` section rather than scraped from the whole file: a
     * substring search over the summary would also match the word in a header, which is
     * how a test ends up asserting that a finding is present when the file merely has a
     * section heading called `findings`.
     */
    fun codesOfFirstCase(): Set<String> {
        var inFindings = false
        return summaryOfFirstCase().lineSequence().mapNotNull { line ->
            when {
                line.startsWith("findings:") -> { inFindings = true; null }
                inFindings && line.startsWith("layers:") -> { inFindings = false; null }
                inFindings -> line.trim().takeIf { it.isNotEmpty() }?.substringBefore(' ')
                else -> null
            }
        }.toSet()
    }

    private var counter = 0

    companion object {
        const val TODAY = "2026-09-29"

        /** A synthetic document frame: light background, a photo box and a microprint band. */
        fun syntheticDocument(target: Path, width: Int = 640, height: Int = 400) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val inPhoto = x in 100..220 && y in 80..200
                    val inBand = y in 300..340
                    val value = when {
                        inPhoto -> if (((x + y) / 3) % 2 == 0) 40 else 210
                        inBand -> if ((x / 2) % 2 == 0) 20 else 235
                        else -> 235
                    }
                    image.setRGB(x, y, Color(value, value, value).rgb)
                }
            }
            Files.createDirectories(target.parent)
            ImageIO.write(image, "png", target.toFile())
        }

        fun validMrz(): List<String> = MrzBuilder.buildTd3(
            MrzPerson(
                surname = "SHARMA",
                givenNames = "RAMESH",
                documentNumber = "AB1234567",
                birthDate = IsoDate(1990, 6, 8),
                expiryDate = IsoDate(2031, 6, 7),
            ),
            referenceYear = 2026,
        )

        fun jsonArray(values: List<String>) =
            values.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }

        fun sidecarJson(
            mrz: List<String>,
            printName: String = "SHARMA RAMESH",
            printDob: String = "1990-06-08",
            extra: String = "",
        ): String = """
            {
              "mrz": ${jsonArray(mrz)},
              "printName": "$printName",
              "printDob": "$printDob",
              "photoZone": {"x": 0.14, "y": 0.18, "w": 0.20, "h": 0.32},
              "textZone":  {"x": 0.05, "y": 0.72, "w": 0.90, "h": 0.12}
              $extra
            }
        """.trimIndent()

        fun generateKey(): Pair<ByteArray, PrivateKey> {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return pair.public.encoded to pair.private
        }

        fun sign(data: ByteArray, key: PrivateKey): ByteArray =
            Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(data)
                sign()
            }

        fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}

/** Run [block] against a fresh console in a temporary directory, then clean it up. */
internal fun withFixture(
    locator: ModelLocator? = null,
    block: (Fixture) -> Unit,
) {
    val dir = createTempDirectory("kasoti-console")
    val fixture = Fixture(dir, locator ?: ModelLocator(env = emptyMap(), installRoot = null, workingDir = dir))
    try {
        block(fixture)
    } finally {
        fixture.dir.toFile().deleteRecursively()
    }
}

/**
 * End-to-end Post-Console tests (FR-S3, FR-S4, invariant I6, NFR-P2).
 *
 * These run the real command surface over real files. The point is not coverage — it is
 * that a *sequence* of operator actions leaves the console in a state an auditor can make
 * sense of: screen, then override, then verify, then export, then wipe.
 */
class ConsoleEndToEndTest {

    // ------------------------------------------------------------------ screen

    @Test
    fun `a valid document with real check digits raises no hard RED`() = withFixture { f ->
        f.screenOnce()
        val codes = f.codesOfFirstCase()
        assertFalse(FindingCode.R_MATH_01.name in codes, "a valid MRZ must not trip R-MATH-01")
        assertFalse(FindingCode.R_MATH_02.name in codes, "an unexpired document must not trip R-MATH-02")
    }

    @Test
    fun `the layers, the policy versions and both languages are printed`() = withFixture { f ->
        f.screenOnce()
        assertContains(f.text, "LAYERS")
        assertContains(f.text, "FINDINGS")
        assertContains(f.text, "thresholds")
        assertContains(f.text, "fusion rules")
        // The operator-facing line is resolved from the worst finding's code, in both
        // languages. A clean case has no worst finding, so this is asserted on a case that
        // actually produced one.
        val mutated = f.dir.resolve("mutated.png")
        Fixture.syntheticDocument(mutated)
        val fields = f.dir.resolve("mutated.json")
        val broken = Fixture.validMrz().toMutableList().also { rows ->
            rows[0] = rows[0].dropLast(1) + if (rows[0].last() == '0') '1' else '0'
        }
        Files.writeString(fields, Fixture.sidecarJson(broken))
        f.run(*f.localScreen(mutated, fields))
        assertContains(f.text, "EN      :")
        assertContains(f.text, "HI      :")
    }

    @Test
    fun `layers that could not run say so rather than reporting a number`() = withFixture { f ->
        f.screenOnce()
        assertContains(f.text, "face")
        assertContains(f.text, "UNAVAILABLE")
        assertContains(f.text, "diary")
    }

    @Test
    fun `a flipped MRZ check digit produces R-MATH-01`() = withFixture { f ->
        val image = f.dir.resolve("doc.png")
        Fixture.syntheticDocument(image)
        // The TD3 composite check digit is the last character of line *1*; the name line
        // ends in a filler, so flipping that would corrupt nothing.
        val mutated = Fixture.validMrz().toMutableList().also { rows ->
            rows[0] = rows[0].dropLast(1) + if (rows[0].last() == '0') '1' else '0'
        }
        val fields = f.dir.resolve("fields.json")
        Files.writeString(fields, Fixture.sidecarJson(mutated))

        f.run(*f.localScreen(image, fields))
        assertContains(f.codesOfFirstCase(), FindingCode.R_MATH_01.name)
        assertContains(f.text, "SECONDARY REVIEW REQUIRED")
    }

    @Test
    fun `an expired document produces R-MATH-02`() = withFixture { f ->
        val image = f.dir.resolve("doc.png")
        Fixture.syntheticDocument(image)
        val expired = MrzBuilder.buildTd3(
            MrzPerson(
                surname = "SHARMA",
                givenNames = "RAMESH",
                documentNumber = "AB1234567",
                birthDate = IsoDate(1990, 6, 8),
                expiryDate = IsoDate(2021, 6, 7),
            ),
            referenceYear = 2026,
        )
        val fields = f.dir.resolve("fields.json")
        Files.writeString(fields, Fixture.sidecarJson(expired))

        f.run(*f.localScreen(image, fields))
        assertContains(f.codesOfFirstCase(), FindingCode.R_MATH_02.name)
    }

    /** Fail-closed: a dark capture is a retake, never an accusation. */
    @Test
    fun `a dark capture is GREY with a retake instruction`() = withFixture { f ->
        val image = f.dir.resolve("dark.png")
        val dark = BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB)
        val g = dark.createGraphics()
        try {
            g.color = Color(4, 4, 4)
            g.fillRect(0, 0, 200, 120)
        } finally {
            g.dispose()
        }
        ImageIO.write(dark, "png", image.toFile())

        f.run("screen", "--image", image.toString(), "--today", Fixture.TODAY, "--fusion", "local")
        assertContains(f.text, "VERDICT : GREY")
        assertContains(f.text, "RETAKE")
        assertContains(f.codesOfFirstCase(), FindingCode.G_DARK.name)
    }

    // --------------------------------------------------------------------- QR

    private fun screenWithQr(
        f: Fixture,
        body: String,
        qrName: String,
        qrDob: String,
        printName: String = "SHARMA RAMESH",
        printDob: String = "1990-06-08",
    ) {
        val image = f.dir.resolve("doc-${System.nanoTime()}.png")
        Fixture.syntheticDocument(image)
        val (x509, privateKey) = Fixture.generateKey()
        val signature = Fixture.sign(body.toByteArray(Charsets.UTF_8), privateKey)
        val fields = f.dir.resolve("fields-${System.nanoTime()}.json")
        Files.writeString(
            fields,
            """
            {
              "printName": "$printName",
              "printDob": "$printDob",
              "qrRawBase64": "${Fixture.base64(body.toByteArray(Charsets.UTF_8))}",
              "qrSignatureBase64": "${Fixture.base64(signature)}",
              "qrX509KeyBase64": "${Fixture.base64(x509)}",
              "qrKeyId": "uidai_test",
              "qrFields": {"name": "$qrName", "dob": "$qrDob"}
            }
            """.trimIndent(),
        )
        f.run(*f.localScreen(image, fields, track = "AADHAAR"))
    }

    @Test
    fun `a QR signature that verifies records Q-SIG-OK, not R-QR-01`() = withFixture { f ->
        screenWithQr(f, """{"version":2,"name":"SHARMA RAMESH","dob":"1990-06-08"}""", "SHARMA RAMESH", "1990-06-08")
        val codes = f.codesOfFirstCase()
        assertContains(codes, FindingCode.Q_SIG_OK.name)
        assertFalse(FindingCode.R_QR_01.name in codes)
    }

    @Test
    fun `a signature over different bytes is R-QR-01`() = withFixture { f ->
        val image = f.dir.resolve("doc.png")
        Fixture.syntheticDocument(image)
        val (x509, privateKey) = Fixture.generateKey()
        val signature = Fixture.sign("a different payload".toByteArray(Charsets.UTF_8), privateKey)
        val body = """{"version":2,"name":"SHARMA RAMESH","dob":"1990-06-08"}"""
        val fields = f.dir.resolve("fields.json")
        Files.writeString(
            fields,
            """
            {
              "qrRawBase64": "${Fixture.base64(body.toByteArray(Charsets.UTF_8))}",
              "qrSignatureBase64": "${Fixture.base64(signature)}",
              "qrX509KeyBase64": "${Fixture.base64(x509)}",
              "qrFields": {"name": "SHARMA RAMESH", "dob": "1990-06-08"}
            }
            """.trimIndent(),
        )
        f.run(*f.localScreen(image, fields, track = "AADHAAR"))
        assertContains(f.codesOfFirstCase(), FindingCode.R_QR_01.name)
    }

    @Test
    fun `a QR disagreeing with the print is R-QR-02`() = withFixture { f ->
        screenWithQr(f, """{"version":2,"name":"SOMEONE ELSE","dob":"1970-01-01"}""", "SOMEONE ELSE", "1970-01-01")
        assertContains(f.codesOfFirstCase(), FindingCode.R_QR_02.name)
    }

    @Test
    fun `an unsigned QR contributes consistency only and raises A-QR-01`() = withFixture { f ->
        val image = f.dir.resolve("doc.png")
        Fixture.syntheticDocument(image)
        val fields = f.dir.resolve("fields.json")
        Files.writeString(
            fields,
            """{"qrFields": {"name": "SOMEONE ELSE", "dob": "1970-01-01"}}""",
        )
        f.run(*f.localScreen(image, fields, track = "AADHAAR"))
        val codes = f.codesOfFirstCase()
        assertContains(codes, FindingCode.A_QR_01.name)
        assertFalse(FindingCode.R_QR_01.name in codes, "an unsigned QR must never be treated as a signature failure")
    }

    // -------------------------------------------------------------- robustness

    @Test
    fun `an unreadable image still produces a decision rather than a crash`() = withFixture { f ->
        val image = f.dir.resolve("doc.png")
        Files.writeString(image, "not an image at all")
        val result = f.run("screen", "--image", image.toString(), "--today", Fixture.TODAY, "--fusion", "local")
        assertEquals(ConsolePolicy.Exit.OK, result.exitCode)
    }

    @Test
    fun `a missing image is a usage error`() = withFixture { f ->
        val result = f.run("screen", "--image", f.dir.resolve("nope.png").toString())
        assertEquals(ConsolePolicy.Exit.USAGE, result.exitCode)
    }

    @Test
    fun `an unknown track is a usage error naming the valid set`() = withFixture { f ->
        Fixture.syntheticDocument(f.dir.resolve("doc.png"))
        val result = f.run("screen", "--image", f.dir.resolve("doc.png").toString(), "--track", "PANJAB")
        assertEquals(ConsolePolicy.Exit.USAGE, result.exitCode)
        assertContains(result.summary, "PASSPORT")
    }

    @Test
    fun `a mistyped option is rejected rather than ignored`() = withFixture { f ->
        Fixture.syntheticDocument(f.dir.resolve("doc.png"))
        assertFailsWith<UsageException> {
            f.run("screen", "--imag", f.dir.resolve("doc.png").toString())
        }
    }

    // --------------------------------------------------------------- demo mode

    @Test
    fun `the demo stub is refused without --demo and accepted with it`() = withFixture { f ->
        val image = f.dir.resolve("doc.png")
        Fixture.syntheticDocument(image)
        val fields = f.dir.resolve("fields.json")
        Files.writeString(fields, Fixture.sidecarJson(Fixture.validMrz()))

        val svm = f.dir.resolve("stub.json")
        Files.writeString(svm, SvmModelFileFixture.untrainedStubJson())

        val refused = assertFailsWith<SvmModelException> {
            f.run(
                "screen", "--image", image.toString(), "--fields", fields.toString(),
                "--today", Fixture.TODAY, "--svm", svm.toString(),
            )
        }
        assertContains(refused.message.orEmpty(), "demo stub")

        val accepted = f.run(
            "screen", "--image", image.toString(), "--fields", fields.toString(),
            "--today", Fixture.TODAY, "--demo",
        )
        assertEquals(ConsolePolicy.Exit.OK, accepted.exitCode)
        assertContains(f.text, "DEMO RUN")
    }

    // ---------------------------------------------------------------- override

    @Test
    fun `an override without a reason code is refused`() = withFixture { f ->
        val caseId = f.screenOnce()
        // `required()` raises rather than returning a sentinel, and `runConsole` turns that
        // into a USAGE exit with a one-line message — no stack trace in front of an
        // operator. Asserted at both levels.
        val failure = assertFailsWith<UsageException> {
            f.run("override", "--case", caseId, "--verdict", "GREEN", "--by", "sup-1")
        }
        assertContains(failure.message.orEmpty(), "reason")
        assertEquals(1, AuditStore(f.home.auditFile).size, "nothing may be appended on a refused override")
    }

    @Test
    fun `an override with an invented reason code is refused`() = withFixture { f ->
        val caseId = f.screenOnce()
        val result = f.run(
            "override", "--case", caseId, "--verdict", "GREEN",
            "--reason", "BECAUSE_I_SAID_SO", "--by", "sup-1",
        )
        assertEquals(ConsolePolicy.Exit.REFUSED, result.exitCode)
        assertContains(result.summary, "SUP_DOC_AUTHENTIC")
    }

    @Test
    fun `SUP_OTHER_JUSTIFIED demands a written note`() = withFixture { f ->
        val caseId = f.screenOnce()
        val refused = f.run(
            "override", "--case", caseId, "--verdict", "GREEN",
            "--reason", ConsolePolicy.REASON_REQUIRING_NOTE, "--by", "sup-1",
        )
        assertEquals(ConsolePolicy.Exit.REFUSED, refused.exitCode)

        val accepted = f.run(
            "override", "--case", caseId, "--verdict", "GREEN",
            "--reason", ConsolePolicy.REASON_REQUIRING_NOTE, "--by", "sup-1",
            "--note", "original laminated card inspected by hand",
        )
        assertEquals(ConsolePolicy.Exit.OK, accepted.exitCode)
    }

    @Test
    fun `overriding an unknown case is refused`() = withFixture { f ->
        val result = f.run(
            "override", "--case", "case_doesnotexist", "--verdict", "GREEN",
            "--reason", "SUP_DOC_AUTHENTIC", "--by", "sup-1",
        )
        assertEquals(ConsolePolicy.Exit.REFUSED, result.exitCode)
    }

    @Test
    fun `an override is appended to the chain and survives a restart`() = withFixture { f ->
        val caseId = f.screenOnce()
        f.run(
            "override", "--case", caseId, "--verdict", "GREEN",
            "--reason", "SUP_DOC_AUTHENTIC", "--by", "sup-1",
        )
        val reopened = AuditStore(f.home.auditFile)
        assertEquals(2, reopened.size)
        val last = reopened.all().last()
        assertEquals("sup-1", last.overriddenBy)
        assertEquals("SUP_DOC_AUTHENTIC", last.overrideReasonCode)
        assertTrue(reopened.verify().valid)
    }

    // ------------------------------------------------------------------ verify

    @Test
    fun `verify passes on a clean chain`() = withFixture { f ->
        f.screenOnce()
        val result = f.run("verify")
        assertEquals(ConsolePolicy.Exit.OK, result.exitCode)
        assertContains(f.text, "VERIFIED")
    }

    @Test
    fun `verify fails loudly when the log was edited`() = withFixture { f ->
        f.screenOnce()
        f.screenOnce()
        val text = Files.readString(f.home.auditFile)
        // The device id, not the verdict: the verdict depends on which engine ran, and a
        // tamper test that silently becomes a no-op when that changes is worse than none.
        Files.writeString(f.home.auditFile, text.replace("desktop-console", "tampered-console"))

        // A *new* console, because that is what actually rebuilds the chain: the running
        // one holds it in memory and would verify its own unedited copy.
        val restarted = Console(f.home)
        val failure = assertFailsWith<ChainIntegrityException> { restarted.run(CommandLine.parse(arrayOf("verify"))) }
        assertContains(failure.message.orEmpty(), "altered")
    }

    @Test
    fun `verify on a console with no log is fine`() = withFixture { f ->
        assertEquals(ConsolePolicy.Exit.OK, f.run("verify").exitCode)
    }

    // ------------------------------------------------------------------ export

    @Test
    fun `a bundle contains case json and the audit excerpt and no live face`() = withFixture { f ->
        val caseId = f.screenOnce()
        val out = f.dir.resolve("bundle.zip")
        assertEquals(ConsolePolicy.Exit.OK, f.run("export", "--case", caseId, "--out", out.toString()).exitCode)

        ZipFile(out.toFile()).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertContains(names, "case.json")
            assertContains(names, "audit.txt")
            assertFalse(names.any { it.contains("LIVE_FACE") }, "NFR-P2: no live face by default")
        }
    }

    @Test
    fun `a bundle is under the 500 KB budget`() = withFixture { f ->
        val caseId = f.screenOnce()
        val out = f.dir.resolve("bundle.zip")
        f.run("export", "--case", caseId, "--out", out.toString())
        assertTrue(Files.size(out) < ConsolePolicy.BUNDLE_SIZE_BUDGET_BYTES)
    }

    /** The bundle leaves the consent boundary, so it must not carry the holder's identity. */
    @Test
    fun `a bundle contains no PII even though the MRZ did`() = withFixture { f ->
        val caseId = f.screenOnce()
        val out = f.dir.resolve("bundle.zip")
        f.run("export", "--case", caseId, "--out", out.toString())

        ZipFile(out.toFile()).use { zip ->
            val everything = zip.entries().toList().joinToString("\n") { entry ->
                String(zip.getInputStream(entry).readBytes(), Charsets.UTF_8)
            }
            assertFalse(everything.contains("SHARMA"), "the holder's surname reached the bundle")
            assertFalse(everything.contains("RAMESH"), "the holder's given names reached the bundle")
            assertFalse(everything.contains("1990-06-08"), "the date of birth reached the bundle")
            assertFalse(everything.contains("AB1234567"), "the document number reached the bundle")
        }
    }

    @Test
    fun `a bundle records that the policy versions and check results are known`() = withFixture { f ->
        val caseId = f.screenOnce()
        val out = f.dir.resolve("bundle.zip")
        f.run("export", "--case", caseId, "--out", out.toString())
        ZipFile(out.toFile()).use { zip ->
            val caseJson = String(zip.getInputStream(zip.getEntry("case.json")).readBytes(), Charsets.UTF_8)
            assertContains(caseJson, "\"piiIncluded\":false")
            assertContains(caseJson, "thresholdVersion")
            assertContains(caseJson, "fusionRuleVersion")
            assertContains(caseJson, "mrzChecks")
            assertContains(caseJson, "findings")
        }
    }

    @Test
    fun `exporting an unknown case is a usage error`() = withFixture { f ->
        val result = f.run("export", "--case", "case_nope")
        assertEquals(ConsolePolicy.Exit.USAGE, result.exitCode)
    }

    // -------------------------------------------------------------------- wipe

    @Test
    fun `wipe without a pin is refused and nothing is destroyed`() = withFixture { f ->
        f.screenOnce()
        assertEquals(ConsolePolicy.Exit.REFUSED, f.run("wipe", "--yes").exitCode)
        assertEquals(1, AuditStore(f.home.auditFile).size)
    }

    @Test
    fun `wipe with a wrong pin is refused and nothing is destroyed`() = withFixture { f ->
        f.screenOnce()
        assertEquals(ConsolePolicy.Exit.REFUSED, f.run("wipe", "--pin", "not-the-pin", "--yes").exitCode)
        assertEquals(1, AuditStore(f.home.auditFile).size)
    }

    @Test
    fun `wipe without confirmation is refused`() = withFixture { f ->
        f.screenOnce()
        Files.writeString(f.home.pinFile, "correct-horse")
        assertEquals(ConsolePolicy.Exit.USAGE, f.run("wipe", "--pin", "correct-horse").exitCode)
        assertEquals(1, AuditStore(f.home.auditFile).size)
    }

    @Test
    fun `wipe with a correct PIN but no confirmation is a usage error, not a silent wipe`() =
        withFixture { f ->
            f.screenOnce()
            Files.writeString(f.home.pinFile, "correct-horse")
            val result = f.run("wipe", "--pin", "correct-horse")
            assertEquals(ConsolePolicy.Exit.USAGE, result.exitCode)
            assertContains(result.summary, "--yes")
        }

    @Test
    fun `wipe with the right pin clears the cases and leaves a marker`() = withFixture { f ->
        f.screenOnce()
        Files.writeString(f.home.pinFile, "correct-horse")
        assertEquals(ConsolePolicy.Exit.OK, f.run("wipe", "--pin", "correct-horse", "--yes").exitCode)
        assertEquals(0, AuditStore(f.home.auditFile).size)
        assertFalse(Files.exists(f.home.pinFile))
        assertContains(Files.readString(f.home.auditFile), "wiped at")
        assertTrue(Files.list(f.home.casesDir).use { it.count() } == 0L)
    }

    @Test
    fun `wipe accepts a sha256 pin file`() = withFixture { f ->
        f.screenOnce()
        Files.writeString(f.home.pinFile, SupervisorPin.PREFIX + SupervisorPin.digestOf("correct-horse"))
        assertEquals(ConsolePolicy.Exit.OK, f.run("wipe", "--pin", "correct-horse", "--yes").exitCode)
    }

    // ------------------------------------------------------------------- macro

    @Test
    fun `the macro command writes a labelled patch and a manifest row`() = withFixture { f ->
        val image = f.dir.resolve("texture.png")
        Fixture.syntheticDocument(image)
        val corpus = f.dir.resolve("corpus")
        val result = f.run(
            "macro", "--image", image.toString(),
            "--source-id", "specimen-laser-a", "--process", "LASER",
            "--light", "sun", "--device", "post3-ph1", "--calib", "calib-001",
            "--root", corpus.toString(), "--rect", "0.15,0.2,0.2,0.3",
        )
        assertEquals(ConsolePolicy.Exit.OK, result.exitCode)
        assertTrue(Files.exists(corpus.resolve("LASER/sun/clip/specimen-laser-a_1.png")))
        assertContains(Files.readString(corpus.resolve("manifest.csv")), "specimen-laser-a,LASER,sun,true")
    }

    @Test
    fun `an unknown process label is a usage error and writes nothing`() = withFixture { f ->
        val image = f.dir.resolve("texture.png")
        Fixture.syntheticDocument(image)
        val corpus = f.dir.resolve("corpus")
        val result = f.run(
            "macro", "--image", image.toString(), "--source-id", "s1",
            "--process", "LASERJET", "--root", corpus.toString(),
        )
        assertEquals(ConsolePolicy.Exit.USAGE, result.exitCode)
        assertFalse(Files.exists(corpus))
    }

    // -------------------------------------------------------------------- help

    @Test
    fun `an unknown command is a usage error`() = withFixture { f ->
        val result = f.run("teleport")
        assertEquals(ConsolePolicy.Exit.USAGE, result.exitCode)
        assertContains(result.summary, "help")
    }

    @Test
    fun `help lists every command and the override vocabulary`() = withFixture { f ->
        f.console.help()
        for (command in listOf("screen", "override", "verify", "export", "wipe", "macro")) {
            assertContains(f.text, command)
        }
        for (reason in ConsolePolicy.OVERRIDE_REASONS) {
            assertContains(f.text, reason)
        }
    }
}
