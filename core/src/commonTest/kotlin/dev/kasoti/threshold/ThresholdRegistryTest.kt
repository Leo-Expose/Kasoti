package dev.kasoti.threshold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The *shipped* registry: that it loads, that it and the enum agree, and that the operating
 * points a verdict depends on are the ones the file says they are.
 *
 * `ThresholdSpecTest` proves the loader rejects bad documents. This proves the document that
 * actually ships is one of the good ones, and — the part worth reading — that the enum and the
 * file hold each other honest. Either one can drift from the other silently: an enum constant
 * with no registry row reads as an exception only when some code path happens to look it up,
 * and a registry row with no enum constant is a threshold nobody can consult. Both directions
 * are checked here so neither can accumulate.
 */
class ThresholdRegistryTest {

    private val spec = THRESHOLD_SPEC

    @Test
    fun theShippedRegistryLoads() {
        assertEquals(ThresholdSpec.REGISTRY_ID, spec.registry)
        assertEquals(ThresholdSpec.SCHEMA_VERSION, spec.schemaVersion)
        assertEquals("v1", spec.version)
        assertTrue(spec.thresholds.isNotEmpty(), "a registry with no thresholds decides nothing")
    }

    @Test
    fun everyEnumConstantHasARegistryRow() {
        val missing = ThresholdName.entries.map { it.name } - spec.thresholds.keys
        assertEquals(emptyList(), missing, "enum constants with no registry row: $missing")
    }

    @Test
    fun everyRegistryRowHasAnEnumConstant() {
        val orphaned = spec.thresholds.keys - ThresholdName.entries.map { it.name }
        assertEquals(emptySet(), orphaned, "registry rows no code can look up: $orphaned")
    }

    @Test
    fun everyEntryIsInsideItsOwnDeclaredRangeAndCarriesItsMetadata() {
        for (name in ThresholdName.entries) {
            assertTrue(name.default in name.floor..name.ceiling, "$name default is out of range")
            assertTrue(name.unit.isNotBlank(), "$name has no unit")
            assertTrue(name.tuningDataRef.isNotBlank(), "$name has no tuning-data ref")
            assertTrue(name.owner.isNotBlank(), "$name has no owner")
        }
    }

    @Test
    fun theShippedVersionIsTheOneTheRegistryDeclares() {
        // ThresholdRegistry.defaults() takes its version from the document. If those ever
        // diverge, a decision record would claim to have been decided under a registry version
        // that never existed, which is the one field a reader trusts to reproduce a verdict.
        assertEquals(spec.version, ThresholdRegistry.defaults().version)
    }

    @Test
    fun defaultsFreezesEveryThresholdAtItsRegistryDefault() {
        val registry = ThresholdRegistry.defaults()
        for (name in ThresholdName.entries) {
            assertEquals(name.default, registry[name], "$name did not load its declared default")
        }
    }

    @Test
    fun anAbsentThresholdThrowsRatherThanReadingAsZero() {
        // The zero is the load-bearing part. 0.0 for a cosine threshold is the most permissive
        // possible setting, and for several thresholds it is also the most alarming one; a
        // lookup that answered 0.0 instead of throwing would let a partially-built registry
        // pass everything (AGENTS.md §4, no silent default-pass).
        val partial = ThresholdRegistry(
            version = spec.version,
            values = mapOf(ThresholdName.T_FACE_RED to 0.35),
            runId = "partial",
            frozenAt = null,
        )
        assertFailsWith<IllegalStateException> { partial[ThresholdName.T_FACE_GREEN] }
    }

    @Test
    fun withValueRefusesToLeaveThePolicyRange() {
        val registry = ThresholdRegistry.defaults()
        val name = ThresholdName.T_FACE_RED
        assertFailsWith<IllegalArgumentException> { registry.withValue(name, name.floor - 0.01) }
        assertFailsWith<IllegalArgumentException> { registry.withValue(name, name.ceiling + 0.01) }
        assertEquals(0.40, registry.withValue(name, 0.40)[name])
    }

    @Test
    fun aChangedOperatingPointIsVisibleInTheCanonicalForm() {
        // I3: a verdict must be reproducible against the operating point that produced it, and
        // the canonical form is what a reader compares between two devices.
        val a = ThresholdRegistry.defaults(runId = "r1")
        val b = a.withValue(ThresholdName.T_FACE_RED, 0.4)
        assertTrue(a.canonical() != b.canonical(), "a re-tuned threshold must change the record")
        assertTrue(b.canonical().contains("\"T_FACE_RED\":0.4"), "got ${b.canonical()}")
    }

    @Test
    fun theFusionTemperRampSumsToOneSoFullQualityStaysTransparent() {
        // The property fuseMatchScore's comment used to assert. It is now a load-time
        // constraint, and this test is what says the shipped numbers still satisfy it.
        val floor = ThresholdName.FUSE_TEMPER_FLOOR.default
        val slope = ThresholdName.FUSE_TEMPER_SLOPE.default
        assertEquals(1.0, floor + slope, 1e-9, "full quality must leave the score unchanged")
    }

    @Test
    fun everyStructuralExemptionIsVouchedFor() {
        for (s in spec.structural) {
            assertTrue(s.reason.isNotBlank(), "${s.id} has no reason")
            assertTrue(s.scope.isNotBlank(), "${s.id} has no scope")
            assertTrue(s.owner.isNotBlank(), "${s.id} has no owner")
            assertTrue(s.enforcedBy.isNotEmpty(), "${s.id} is enforced by nobody")
        }
        assertTrue(spec.structural.isNotEmpty(), "a registry with no exemptions cannot justify a magic number")
    }
}
