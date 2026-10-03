package dev.kasoti.threshold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registry loader's contract, exercised without touching the shipped document.
 *
 * Every fixture here is a *string*, including the valid ones, because that is the only way to
 * feed the loader a registry that is wrong in a specific way. Testing the parser with documents
 * that were built by the same code that will parse them proves nothing about malformed input.
 *
 * The tests that matter most are the failures. A registry is the input to every verdict's
 * arithmetic, so "loaded successfully" has to mean "and I understood every part of it" — an
 * unknown field, a missing field, an unknown schema version, a default outside its own range
 * and an empty document must all be errors. The one thing a threshold loader must never do is
 * load *partially*: a registry that drops the one entry it could not parse hands the verdict
 * path a missing operating point, and the only sensible-looking thing to do with one of those
 * is 0.0, which reads as "nothing detected" (AGENTS.md §4, no silent default-pass).
 */
class ThresholdSpecTest {

    // --- a minimal well-formed document, built as text so each test can break one field ---
    //
    // Fields are assembled from a map and rendered, rather than produced by string surgery on a
    // finished document. Half of the tests here *remove* or *rename* a field, and doing that
    // with `replace` produces documents that are malformed in some other, uninteresting way --
    // which would let a test pass for a reason that has nothing to do with the field it names.

    private fun entry(
        name: String = "T_DEMO",
        drop: Set<String> = emptySet(),
        extra: Map<String, String> = emptyMap(),
    ): String {
        val fields = linkedMapOf(
            "name" to "\"$name\"",
            "unit" to "\"cosine\"",
            "default" to "0.5",
            "floor" to "0.1",
            "ceiling" to "0.9",
            "tuningDataRef" to "\"D-DEMO\"",
            "owner" to "\"vision\"",
        )
        fields.keys.toList().forEach { if (it in drop) fields.remove(it) }
        val rendered = (fields + extra).entries.joinToString(",") { "\"${it.key}\": ${it.value}" }
        return "{$rendered}"
    }

    /**
     * Overrides for [document], as an object rather than ten default arguments.
     *
     * detekt's LongParameterList flagged the ten-parameter version, and it was right: a call
     * site like `document(Overrides(thresholds = ..., units = ..., constraints = ...))` was hard to read
     * and impossible to extend without touching every caller.
     */
    private data class Overrides(
        val thresholds: String? = null,
        val schemaVersion: String? = null,
        val registry: String? = null,
        val version: String? = null,
        val units: String? = null,
        val constraints: String? = null,
        val structural: String? = null,
        val omitThresholdArray: Boolean = false,
        val omitStructural: Boolean = false,
        val topLevelExtra: String = "",
    )

    private fun document(o: Overrides = Overrides()): String {
        val parts = mutableListOf(
            "\"registry\": ${o.registry ?: "\"${ThresholdSpec.REGISTRY_ID}\""}",
            "\"schemaVersion\": ${o.schemaVersion ?: "1"}",
            "\"version\": ${o.version ?: "\"v1\""}",
        )
        o.units?.let { parts += "\"units\": $it" }
        o.constraints?.let { parts += "\"constraints\": [$it]" }
        if (!o.omitThresholdArray) parts += "\"thresholds\": [${o.thresholds ?: entry()}]"
        if (!o.omitStructural) parts += "\"structural\": [${o.structural ?: structuralDefault}]"
        if (o.topLevelExtra.isNotEmpty()) parts += o.topLevelExtra
        return "{${parts.joinToString(",")}}"
    }

    private val structuralDefault =
        """{"id":"S-DEMO","enforcedBy":["script"],"scope":"a fixture",""" +
            """"owner":"vision","reason":"because it is a fixture"}"""

    private fun sumToOne(members: String): String =
        """{"kind":"sumToOne","members":$members,"tolerance":1e-9}"""

    private fun structuralWith(
        enforcedBy: String? = null,
        dropReason: Boolean = false,
    ): String {
        val fields = linkedMapOf(
            "id" to "\"S-DEMO\"",
            "enforcedBy" to (enforcedBy ?: "[\"script\"]"),
            "scope" to "\"a fixture\"",
            "owner" to "\"vision\"",
        )
        if (!dropReason) fields["reason"] = "\"because it is a fixture\""
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private fun assertRejected(text: String, expectedFragment: String) {
        val thrown = assertFailsWith<ThresholdSpecException>("expected rejection of: $text") {
            ThresholdSpec.parse(text)
        }
        assertTrue(
            thrown.message!!.contains(expectedFragment),
            "message '${thrown.message}' did not mention '$expectedFragment'",
        )
        assertNull(ThresholdSpec.parseOrNull(text), "parseOrNull must agree that this is invalid")
    }

    // --- the happy path ------------------------------------------------------------------------------

    @Test
    fun aMinimalWellFormedRegistryLoads() {
        val spec = ThresholdSpec.parse(document())
        assertEquals(ThresholdSpec.REGISTRY_ID, spec.registry)
        assertEquals(1, spec.schemaVersion)
        assertEquals("v1", spec.version)
        val loaded = spec.entry("T_DEMO")
        assertEquals(0.5, loaded.default)
        assertEquals(0.1, loaded.floor)
        assertEquals(0.9, loaded.ceiling)
        assertEquals("cosine", loaded.unit)
        assertEquals("D-DEMO", loaded.tuningDataRef)
        assertEquals("vision", loaded.owner)
        assertNull(loaded.rationale, "rationale is optional")
    }

    @Test
    fun anOptionalRationaleIsCarriedThrough() {
        val spec = ThresholdSpec.parse(document(Overrides(thresholds = entry(extra = mapOf("rationale" to "\"why\"")))))
        assertEquals("why", spec.entry("T_DEMO").rationale)
    }

    @Test
    fun structuralExemptionsAreCarriedThroughWithTheirReasonAndEnforcer() {
        val spec = ThresholdSpec.parse(document())
        val s = spec.structural.single()
        assertEquals("S-DEMO", s.id)
        assertEquals("because it is a fixture", s.reason)
        assertEquals(setOf(StructuralExemption.ENFORCED_BY_SCRIPT), s.enforcedBy)
        assertEquals(setOf("S-DEMO"), spec.scriptEnforcedExemptionIds())
    }

    // --- unknown keys (AGENTS.md §2: a typo must not be silently ignored) ----------------------------

    @Test
    fun anUnknownEntryFieldIsRejected() {
        assertRejected(document(Overrides(thresholds = entry(extra = mapOf("florr" to "0.2")))), "unknown field")
    }

    @Test
    fun aMisspelledRequiredEntryFieldIsRejectedRatherThanDefaulted() {
        // The dangerous case: 'floor' becomes 'florr', the entry still looks complete, and the
        // loader would have accepted a threshold with no policy range at all.
        val misspelled = entry(drop = setOf("floor"), extra = mapOf("florr" to "0.2"))
        assertRejected(document(Overrides(thresholds = misspelled)), "unknown field")
    }

    @Test
    fun anUnknownTopLevelFieldIsRejected() {
        assertRejected(document(Overrides(topLevelExtra = "\"author\": \"me\"")), "unknown top-level field")
    }

    @Test
    fun aUnitUsedButNotDescribedInTheLegendIsRejected() {
        assertRejected(
            document(Overrides(units = """{"ratio":"a quotient"}""")),
            "not described in 'units'",
        )
    }

    // --- missing required fields ------------------------------------------------------------------------

    @Test
    fun everyRequiredEntryFieldIsRequired() {
        // One case per field, so a failure names the field that stopped being mandatory.
        for (field in ThresholdSpec.REQUIRED_ENTRY_FIELDS) {
            assertRejected(
                document(Overrides(thresholds = entry(drop = setOf(field)))),
                "missing required field",
            )
        }
    }

    @Test
    fun aMissingTopLevelFieldIsRejected() {
        assertRejected(document(Overrides(omitThresholdArray = true)), "missing required field 'thresholds'")
        assertRejected(document(Overrides(omitStructural = true)), "missing required field 'structural'")
    }

    @Test
    fun aWrongTypeIsRejectedRatherThanCoerced() {
        assertRejected(
            document(Overrides(thresholds = entry(extra = mapOf("default" to "\"0.5\"")))),
            "must be a number",
        )
        assertRejected(document(Overrides(thresholds = entry(extra = mapOf("name" to "7")))), "must be a string")
        assertRejected(document(Overrides(thresholds = "[1, 2, 3]")), "must be an object")
    }

    // --- range, uniqueness and ordering ------------------------------------------------------------------

    @Test
    fun aDefaultOutsideItsOwnDeclaredRangeIsRejected() {
        assertRejected(
            document(Overrides(thresholds = entry(extra = mapOf("default" to "0.95")))),
            "outside its declared",
        )
        assertRejected(
            document(Overrides(thresholds = entry(extra = mapOf("default" to "0.05")))),
            "outside its declared",
        )
    }

    @Test
    fun aFloorAboveItsCeilingIsRejected() {
        val inverted = entry(drop = setOf("floor", "ceiling"), extra = mapOf("floor" to "0.8", "ceiling" to "0.2"))
        assertRejected(document(Overrides(thresholds = inverted)), "above ceiling")
    }

    @Test
    fun aBoundaryDefaultIsAccepted() {
        val atFloor = ThresholdSpec.parse(document(Overrides(thresholds = entry(extra = mapOf("default" to "0.1")))))
        assertEquals(0.1, atFloor.entry("T_DEMO").default, "a default exactly on its floor is legal")
        val atCeiling = ThresholdSpec.parse(document(Overrides(thresholds = entry(extra = mapOf("default" to "0.9")))))
        assertEquals(0.9, atCeiling.entry("T_DEMO").default, "a default exactly on its ceiling is legal")
    }

    @Test
    fun aThresholdDeclaredTwiceIsRejected() {
        assertRejected(document(Overrides(thresholds = entry() + "," + entry())), "declared twice")
    }

    @Test
    fun lookingUpAnUndeclaredThresholdThrowsRatherThanDefaulting() {
        val spec = ThresholdSpec.parse(document())
        assertFailsWith<ThresholdSpecException> { spec.entry("T_ABSENT") }
            .also { assertTrue(it.message!!.contains("T_ABSENT")) }
    }

    // --- versioning ---------------------------------------------------------------------------------------

    @Test
    fun anUnknownSchemaVersionIsRefusedRatherThanGuessedAt() {
        assertRejected(document(Overrides(schemaVersion = "2")), "schemaVersion 2 is not supported")
        assertRejected(document(Overrides(schemaVersion = "\"1\"")), "must be an integer")
    }

    @Test
    fun aForeignRegistryIdIsRefused() {
        assertRejected(document(Overrides(registry = "\"someone.elses.thresholds\"")), "registry id is")
    }

    // --- cross-threshold invariants ------------------------------------------------------------------------

    @Test
    fun aSumToOneConstraintIsAcceptedWhenItHolds() {
        val two = entry(name = "A") + "," +
            entry(name = "B", extra = mapOf("unit" to "\"ratio\""))
        assertEquals(
            2,
            ThresholdSpec.parse(
                document(Overrides(thresholds = two,
                    units = """{"cosine":"c","ratio":"r"}""",
                    constraints = sumToOne("""["A","B"]"""),)),
            ).thresholds.size,
        )
    }

    @Test
    fun aSumToOneConstraintIsEnforcedAtLoad() {
        val broken = entry(name = "A", extra = mapOf("default" to "0.6")) + "," +
            entry(name = "B", extra = mapOf("unit" to "\"ratio\"", "default" to "0.9"))
        assertRejected(
            document(Overrides(thresholds = broken,
                units = """{"cosine":"c","ratio":"r"}""",
                constraints = sumToOne("""["A","B"]"""),)),
            "sum to",
        )
    }

    @Test
    fun aConstraintNamingAnUndeclaredThresholdIsRejected() {
        assertRejected(document(Overrides(constraints = sumToOne("""["A","NOPE"]"""))), "not a declared threshold")
    }

    @Test
    fun anUnknownConstraintKindIsRefusedRatherThanIgnored() {
        assertRejected(
            document(Overrides(constraints = """{"kind":"mustBeEven","members":["T_DEMO"],"tolerance":0.0}""")),
            "unknown constraint kind",
        )
    }

    @Test
    fun aMissingConstraintToleranceIsRejected() {
        assertRejected(
            document(Overrides(constraints = """{"kind":"sumToOne","members":["T_DEMO"]}""")),
            "must be a number",
        )
    }

    // --- structural exemptions ------------------------------------------------------------------------------

    @Test
    fun aStructuralExemptionWithNoEnforcerIsRejected() {
        assertRejected(document(Overrides(structural = structuralWith(enforcedBy = "[]"))), "declares no enforcer")
    }

    @Test
    fun aStructuralExemptionWithAnUnknownEnforcerIsRejected() {
        assertRejected(
            document(Overrides(structural = structuralWith(enforcedBy = """["vibes"]"""))),
            "unknown enforcer",
        )
    }

    @Test
    fun aStructuralExemptionWithoutAReasonIsRejected() {
        assertRejected(document(Overrides(structural = structuralWith(dropReason = true))), "reason must be a string")
    }

    @Test
    fun aStructuralExemptionDeclaredTwiceIsRejected() {
        assertRejected(document(Overrides(structural = "$structuralDefault,$structuralDefault")), "declared twice")
    }

    // --- adversarial: fail closed, never load partially --------------------------------------------------------

    @Test
    fun anEmptyOrBlankDocumentIsRejected() {
        assertRejected("", "empty")
        assertRejected("   \n\t ", "empty")
    }

    @Test
    fun aNonObjectDocumentIsRejected() {
        for (text in listOf("[]", "null", "0", "\"v1\"", "true")) {
            assertFailsWith<ThresholdSpecException>("expected rejection of $text") { ThresholdSpec.parse(text) }
        }
    }

    @Test
    fun malformedJsonIsRejectedRatherThanPartiallyRead() {
        for (text in listOf("{", "{}", "{\"registry\":}", "{\"registry\":\"x\",}", "not json at all")) {
            assertFailsWith<ThresholdSpecException>("expected rejection of $text") { ThresholdSpec.parse(text) }
        }
    }

    @Test
    fun aDocumentWithAnEmptyThresholdArrayIsRejected() {
        // The failure this one exists for: an array that parsed fine, is the right type, and
        // carries nothing. Accepting it would give every consumer a registry where every lookup
        // misses -- and a lookup that misses must never look like an operating point of zero.
        assertRejected(document(Overrides(thresholds = "")), "is empty")
    }

    @Test
    fun aTruncationOfAValidDocumentIsRejected() {
        // Every prefix of a valid document must fail rather than half-load. This is the closest
        // a unit test gets to the real failure (a half-written file in a commit) and it is the
        // one that would otherwise ship a registry missing its last few thresholds.
        val full = document()
        for (cut in 1 until full.length step 7) {
            val prefix = full.substring(0, cut)
            assertFailsWith<ThresholdSpecException>("prefix of length $cut was accepted") {
                ThresholdSpec.parse(prefix)
            }
        }
    }
}
