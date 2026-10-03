package dev.kasoti.threshold

import dev.kasoti.json.JsonParser
import dev.kasoti.json.JsonSyntaxException
import dev.kasoti.json.JsonValue
import dev.kasoti.json.array
import dev.kasoti.json.long
import dev.kasoti.json.number
import dev.kasoti.json.obj
import dev.kasoti.json.string

/**
 * Thrown for any registry that is not exactly what the schema requires.
 *
 * An `IllegalArgumentException` subtype so that it composes with the rest of `:core`'s
 * validation, and deliberately *not* a nullable-returning parse: a caller that wants the
 * lenient form has to ask for [ThresholdSpec.parseOrNull] by name, which makes "I chose to
 * tolerate this" a visible decision rather than the path of least resistance.
 */
class ThresholdSpecException(message: String) : IllegalArgumentException(message)

/**
 * One tunable as declared in `fusion/thresholds.v1.json`.
 *
 * [floor] and [ceiling] are the range an operating point may never cross without lead sign-off
 * (AGENTS.md §8: never weaken a gate to make something pass — a widened range is a gate change,
 * so it has to be a reviewed edit to this file, which is what it is).
 */
data class ThresholdSpecEntry(
    val name: String,
    val unit: String,
    val default: Double,
    val floor: Double,
    val ceiling: Double,
    val tuningDataRef: String,
    val owner: String,
    val rationale: String?,
) {
    init {
        require(name.isNotBlank()) { "a threshold entry needs a name" }
        require(unit.isNotBlank()) { "threshold $name needs a unit" }
        require(tuningDataRef.isNotBlank()) { "threshold $name needs a tuning-data ref" }
        require(owner.isNotBlank()) { "threshold $name needs an owner" }
    }

    fun contains(value: Double): Boolean = value in floor..ceiling
}

/**
 * A declared NON-tunable class of numbers, with the argument for why it is not a tunable.
 *
 * This is the other half of AGENTS.md §2. A registry that only listed tunables would leave the
 * gate demanding an entry for `146097`, and the honest answer to that demand is a reason, not a
 * threshold. Each of these is a *category* rather than a per-value list, because the thing
 * being vouched for is the property ("fixed by ICAO 9303"), not the individual integers, and a
 * 200-line list of "and this one too" would be a list nobody re-reasons when the standard moves.
 *
 * [enforcedBy] is what keeps these from becoming a way to switch the gate off. `script` means
 * `scripts/check_no_magic_thresholds.sh` implements a matching rule; `review` means it is a
 * documented decision a human must honour. `scripts/check_no_magic_thresholds.sh` fails with
 * exit 2 if a `script` exemption has no rule there, or a rule there has no exemption here, so
 * neither side can be widened alone.
 */
data class StructuralExemption(
    val id: String,
    val enforcedBy: Set<String>,
    val scope: String,
    val owner: String,
    val reason: String,
) {
    companion object {
        const val ENFORCED_BY_SCRIPT = "script"
        const val ENFORCED_BY_REVIEW = "review"
        val ENFORCED_BY_ALL: Set<String> = setOf(ENFORCED_BY_SCRIPT, ENFORCED_BY_REVIEW)
    }
}

/**
 * The parsed registry: the tunables, the non-tunables, and the cross-checks between them.
 *
 * Every parse is total or it throws. There is no "load what we can" path and no default set,
 * because a registry that quietly drops the one threshold it could not parse is exactly how a
 * verdict starts reading 0.0 for a missing operating point (AGENTS.md §4: no silent
 * default-pass). An empty document, a truncated document, an unknown field, a missing field, a
 * default outside its own declared range and an unknown schema version are all failures.
 */
data class ThresholdSpec(
    val registry: String,
    val schemaVersion: Int,
    val version: String,
    val thresholds: Map<String, ThresholdSpecEntry>,
    val units: Map<String, String>,
    val structural: List<StructuralExemption>,
) {
    /** @throws ThresholdSpecException when [name] is not declared. Never returns a default. */
    fun entry(name: String): ThresholdSpecEntry =
        thresholds[name] ?: throw ThresholdSpecException(
            "threshold '$name' is not declared in registry $version ($registry). " +
                "Declared: ${thresholds.keys.sorted().joinToString(", ")}",
        )

    /** The ids the magic-number script must implement a rule for. */
    fun scriptEnforcedExemptionIds(): Set<String> =
        structural.filterTo(mutableSetOf()) { StructuralExemption.ENFORCED_BY_SCRIPT in it.enforcedBy }
            .mapTo(mutableSetOf()) { it.id }

    companion object {
        const val REGISTRY_ID = "kasoti.thresholds"

        /** Bump when the shape changes; a loader that does not know this version must refuse. */
        const val SCHEMA_VERSION = 1

        const val CONSTRAINT_SUM_TO_ONE = "sumToOne"

        val REQUIRED_ENTRY_FIELDS: Set<String> =
            setOf("name", "unit", "default", "floor", "ceiling", "tuningDataRef", "owner")

        val ALLOWED_ENTRY_FIELDS: Set<String> = REQUIRED_ENTRY_FIELDS + "rationale"

        val REQUIRED_TOP_LEVEL_FIELDS: Set<String> =
            setOf("registry", "schemaVersion", "version", "thresholds", "structural")

        val ALLOWED_TOP_LEVEL_FIELDS: Set<String> =
            REQUIRED_TOP_LEVEL_FIELDS + setOf("authority", "units", "constraints")

        /** @throws ThresholdSpecException on anything the schema does not accept. */
        fun parse(text: String): ThresholdSpec {
            if (text.isBlank()) {
                throw ThresholdSpecException("registry document is empty; there is nothing to load")
            }
            val root = try {
                JsonParser.parse(text)
            } catch (e: JsonSyntaxException) {
                throw ThresholdSpecException("registry is not well-formed JSON: ${e.message}")
            }
            val obj = root as? JsonValue.Obj
                ?: throw ThresholdSpecException("registry must be a JSON object, was ${root.shape()}")

            rejectUnknownTopLevelFields(obj)
            for (field in REQUIRED_TOP_LEVEL_FIELDS) {
                if (field !in obj.fields) {
                    throw ThresholdSpecException("registry is missing required field '$field'")
                }
            }

            val registry = obj.string("registry")
                ?: throw ThresholdSpecException("'registry' must be a string")
            if (registry != REGISTRY_ID) {
                throw ThresholdSpecException("registry id is '$registry', expected '$REGISTRY_ID'")
            }

            val schemaVersion = obj.long("schemaVersion")
                ?: throw ThresholdSpecException("'schemaVersion' must be an integer")
            if (schemaVersion != SCHEMA_VERSION.toLong()) {
                // Refusing an unknown schema is the whole point of versioning it: guessing at
                // the meaning of a field this build has never seen is how a registry gets read
                // as "valid" while saying something else.
                throw ThresholdSpecException(
                    "registry schemaVersion $schemaVersion is not supported; this build reads $SCHEMA_VERSION",
                )
            }

            val version = obj.string("version")
                ?: throw ThresholdSpecException("'version' must be a string")
            if (version.isBlank()) throw ThresholdSpecException("'version' must not be blank")

            val spec = ThresholdSpec(
                registry = registry,
                schemaVersion = schemaVersion.toInt(),
                version = version,
                thresholds = parseThresholds(obj),
                units = parseUnits(obj),
                structural = parseStructural(obj),
            )
            spec.checkUnitsDeclared()
            spec.checkConstraints(obj)
            return spec
        }

        /** @return the parsed registry, or `null` for malformed input. */
        fun parseOrNull(text: String): ThresholdSpec? = try {
            parse(text)
        } catch (_: ThresholdSpecException) {
            null
        }

        private fun parseThresholds(obj: JsonValue.Obj): Map<String, ThresholdSpecEntry> {
            val items = obj.array("thresholds")
                ?: throw ThresholdSpecException("'thresholds' must be an array")
            if (items.isEmpty()) {
                throw ThresholdSpecException("'thresholds' is empty; an empty registry cannot decide anything")
            }
            val out = LinkedHashMap<String, ThresholdSpecEntry>(items.size)
            items.forEachIndexed { index, item ->
                val entryObj = item as? JsonValue.Obj
                    ?: throw ThresholdSpecException("thresholds[$index] must be an object, was ${item.shape()}")
                val entry = parseEntry(entryObj, index)
                val clash = out.put(entry.name, entry)
                if (clash != null) {
                    throw ThresholdSpecException("threshold '${entry.name}' is declared twice")
                }
            }
            return out
        }

        private fun parseEntry(obj: JsonValue.Obj, index: Int): ThresholdSpecEntry {
            val where = "thresholds[$index]"
            val unknown = obj.fields.keys - ALLOWED_ENTRY_FIELDS
            if (unknown.isNotEmpty()) {
                // Rejecting unknown keys is what makes adding a field to the schema a deliberate
                // act. Tolerating them would mean a typo'd 'florr' is silently ignored and the
                // entry loads with no floor at all.
                throw ThresholdSpecException("$where has unknown field(s): ${unknown.sorted().joinToString(", ")}")
            }
            val missing = REQUIRED_ENTRY_FIELDS - obj.fields.keys
            if (missing.isNotEmpty()) {
                throw ThresholdSpecException(
                    "$where is missing required field(s): ${missing.sorted().joinToString(", ")}",
                )
            }
            val name = obj.string("name")
                ?: throw ThresholdSpecException("$where.field 'name' must be a string")
            val floor = finite(obj, where, "floor")
            val default = finite(obj, where, "default")
            val ceiling = finite(obj, where, "ceiling")
            if (floor > ceiling) {
                throw ThresholdSpecException("threshold $name declares floor $floor above ceiling $ceiling")
            }
            if (default < floor || default > ceiling) {
                throw ThresholdSpecException(
                    "threshold $name default $default is outside its declared policy range [$floor, $ceiling]",
                )
            }
            return ThresholdSpecEntry(
                name = name,
                unit = obj.string("unit") ?: throw ThresholdSpecException("$where.field 'unit' must be a string"),
                default = default,
                floor = floor,
                ceiling = ceiling,
                tuningDataRef = obj.string("tuningDataRef")
                    ?: throw ThresholdSpecException("$where.field 'tuningDataRef' must be a string"),
                owner = obj.string("owner") ?: throw ThresholdSpecException("$where.field 'owner' must be a string"),
                rationale = obj.string("rationale"),
            )
        }

        private fun parseUnits(obj: JsonValue.Obj): Map<String, String> {
            val unitsObj = obj.obj("units") ?: return emptyMap()
            val out = LinkedHashMap<String, String>(unitsObj.fields.size)
            for ((unit, value) in unitsObj.fields) {
                out[unit] = (value as? JsonValue.Str)?.value
                    ?: throw ThresholdSpecException("units.$unit must be a string")
            }
            return out
        }

        private fun parseStructural(obj: JsonValue.Obj): List<StructuralExemption> {
            val items = obj.array("structural")
                ?: throw ThresholdSpecException("'structural' must be an array")
            val seen = mutableSetOf<String>()
            return items.mapIndexed { index, item ->
                val where = "structural[$index]"
                val entry = item as? JsonValue.Obj
                    ?: throw ThresholdSpecException("$where must be an object, was ${item.shape()}")
                val id = entry.string("id")
                    ?: throw ThresholdSpecException("$where.field 'id' must be a string")
                if (!seen.add(id)) throw ThresholdSpecException("structural id '$id' is declared twice")
                val enforcedBy = entry.array("enforcedBy")?.map {
                    (it as? JsonValue.Str)?.value
                        ?: throw ThresholdSpecException("$where.field 'enforcedBy' must hold only strings")
                }?.toSet() ?: throw ThresholdSpecException("$where.field 'enforcedBy' must be an array")
                if (enforcedBy.isEmpty()) {
                    throw ThresholdSpecException("$where ($id) declares no enforcer; every exemption needs one")
                }
                val unknownEnforcers = enforcedBy - StructuralExemption.ENFORCED_BY_ALL
                if (unknownEnforcers.isNotEmpty()) {
                    throw ThresholdSpecException(
                        "$where ($id) declares unknown enforcer(s): ${unknownEnforcers.sorted().joinToString(", ")}",
                    )
                }
                StructuralExemption(
                    id = id,
                    enforcedBy = enforcedBy,
                    scope = entry.string("scope")
                        ?: throw ThresholdSpecException("$where ($id).scope must be a string"),
                    owner = entry.string("owner")
                        ?: throw ThresholdSpecException("$where ($id).owner must be a string"),
                    reason = entry.string("reason")
                        ?: throw ThresholdSpecException("$where ($id).reason must be a string"),
                )
            }
        }

        private fun finite(obj: JsonValue.Obj, where: String, field: String): Double {
            val value = obj.number(field)
                ?: throw ThresholdSpecException("$where.field '$field' must be a number")
            if (!value.isFinite()) {
                throw ThresholdSpecException("$where.field '$field' must be finite, was $value")
            }
            return value
        }

        private fun rejectUnknownTopLevelFields(obj: JsonValue.Obj) {
            val unknown = obj.fields.keys - ALLOWED_TOP_LEVEL_FIELDS
            if (unknown.isNotEmpty()) {
                throw ThresholdSpecException(
                    "registry has unknown top-level field(s): ${unknown.sorted().joinToString(", ")}. " +
                        "Add them to ALLOWED_TOP_LEVEL_FIELDS deliberately, not by widening the check here.",
                )
            }
        }
    }

    /** A unit used by a threshold but absent from the legend is a typo until proven otherwise. */
    private fun checkUnitsDeclared() {
        if (units.isEmpty()) return
        val undeclared = thresholds.values.map { it.unit }.toSet() - units.keys
        if (undeclared.isNotEmpty()) {
            throw ThresholdSpecException(
                "unit(s) used but not described in 'units': ${undeclared.sorted().joinToString(", ")}",
            )
        }
    }

    /**
     * Cross-threshold invariants, checked once at load so no verdict path pays for them.
     *
     * The failure mode this replaces is the interesting one: `FUSE_TEMPER_FLOOR` and
     * `FUSE_TEMPER_SLOPE` are two halves of one affine ramp, and if an edit moved only one the
     * code would still compile, still run, and would shift every fused score by however much it
     * moved. Nothing at the call site could notice. A load-time assertion turns that into a
     * startup failure with a message naming the members.
     */
    private fun checkConstraints(obj: JsonValue.Obj) {
        val items = obj.array("constraints") ?: return
        items.forEachIndexed { index, item ->
            val where = "constraints[$index]"
            val entry = item as? JsonValue.Obj
                ?: throw ThresholdSpecException("$where must be an object, was ${item.shape()}")
            val kind = entry.string("kind")
                ?: throw ThresholdSpecException("$where.field 'kind' must be a string")
            if (kind != CONSTRAINT_SUM_TO_ONE) {
                throw ThresholdSpecException(
                    "$where declares unknown constraint kind '$kind'; this build knows only '$CONSTRAINT_SUM_TO_ONE'",
                )
            }
            val members = entry.array("members")?.map {
                (it as? JsonValue.Str)?.value
                    ?: throw ThresholdSpecException("$where.field 'members' must hold only strings")
            } ?: throw ThresholdSpecException("$where.field 'members' must be an array")
            if (members.isEmpty()) {
                throw ThresholdSpecException("$where declares no members")
            }
            val tolerance = entry.number("tolerance")
                ?: throw ThresholdSpecException("$where.field 'tolerance' must be a number")
            if (tolerance < 0.0) throw ThresholdSpecException("$where.field 'tolerance' must not be negative")
            members.forEach { member ->
                if (member !in thresholds) {
                    throw ThresholdSpecException("$where names '$member', which is not a declared threshold")
                }
            }
            val sum = members.sumOf { thresholds.getValue(it).default }
            if (kotlin.math.abs(sum - 1.0) > tolerance) {
                throw ThresholdSpecException(
                    "$where violated: ${members.joinToString(" + ")} defaults sum to $sum, not 1.0 +/- $tolerance",
                )
            }
        }
    }
}

/** The parsed shipped registry. Parsed once; every failure is a startup failure by design. */
internal val THRESHOLD_SPEC: ThresholdSpec by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    ThresholdSpec.parse(ThresholdSpecSource.TEXT)
}

private fun JsonValue.shape(): String = when (this) {
    is JsonValue.Arr -> "an array"
    is JsonValue.Bool -> "a boolean"
    is JsonValue.F32 -> "a number"
    is JsonValue.Num -> "a number"
    is JsonValue.Null -> "null"
    is JsonValue.Real -> "a number"
    is JsonValue.Str -> "a string"
    is JsonValue.Verbatim -> "a string"
    is JsonValue.Obj -> "an object"
}
