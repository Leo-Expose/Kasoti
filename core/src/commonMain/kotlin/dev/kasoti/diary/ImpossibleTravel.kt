package dev.kasoti.diary

import dev.kasoti.fusion.TravelFlag
import dev.kasoti.threshold.ThresholdName
import dev.kasoti.threshold.ThresholdRegistry
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A border post's location. Coordinates are operational data, not PII, but they are still the
 * thing an attacker would use to learn where a post *is*, so they live here rather than being
 * guessed from the post name.
 */
data class PostCoord(val post: String, val latDeg: Double, val lonDeg: Double) {
    init {
        require(latDeg in -90.0..90.0) { "latitude $latDeg out of range" }
        require(lonDeg in -180.0..180.0) { "longitude $lonDeg out of range" }
    }
}

typealias PostMap = Map<String, PostCoord>

/**
 * R-TRAV-01: implied travel speed between two crossings exceeds policy.
 *
 * Great-circle distance, because the alternative — as the crow flies vs as the road winds —
 * matters at exactly the scale being tested. Two posts 60 km apart across a mountain range are
 * 40 km apart in a straight line, and using the winding distance would let a genuine
 * impossible-travel case pass. The bias of haversine is towards *under*-stating distance, which
 * makes the rule conservative in the right direction: it fires less readily, never more.
 *
 * The cost is the opposite bias at the margins: a pair whose true distance is marginally over
 * the threshold may be reported as under it. That is the right way round for a RED-candidate
 * rule that a supervisor reviews.
 */
fun impossibleTravel(
    events: List<CrossingEvent>,
    posts: PostMap,
    vMaxKmh: Double,
    maxFlags: Int,
): List<TravelFlag> {
    require(vMaxKmh > 0.0) { "vMax must be positive, got $vMaxKmh" }
    if (events.size < 2) return emptyList()

    val dated = events.mapNotNull { e ->
        val at = IsoInstant.parse(e.ts) ?: return@mapNotNull null
        Triple(e, at, posts[e.post])
    }
    val flags = mutableListOf<TravelFlag>()
    for (i in dated.indices) {
        val (from, fromAt, fromPost) = dated[i]
        if (fromPost == null) continue
        for (j in i + 1 until dated.size) {
            val (to, toAt, toPost) = dated[j]
            if (toPost == null || to.post == from.post) continue
            val deltaMillis = Math.abs(toAt - fromAt)
            // Zero or negative elapsed time is not a speed, and the common cause is clock skew
            // on one of the two devices. Firing on it would be a false RED built on a broken
            // clock — an incident, not a detection (AGENTS.md §9).
            if (deltaMillis <= 0L) continue
            val hours = deltaMillis / 3_600_000.0
            val distance = haversineKm(fromPost, toPost)
            val speed = distance / hours
            if (speed <= vMaxKmh) continue
            flags += TravelFlag(
                fromEventId = from.id,
                toEventId = to.id,
                fromPost = from.post,
                toPost = to.post,
                distanceKm = round3(distance),
                hours = round3(hours),
                impliedSpeedKmh = round3(speed),
            )
        }
    }
    flags.sortWith(
        compareByDescending<TravelFlag> { it.impliedSpeedKmh }
            .thenBy { it.fromEventId }
            .thenBy { it.toEventId },
    )
    return flags.take(maxFlags)
}

/** Registry-driven overload — the form production code should call. */
fun impossibleTravel(
    events: List<CrossingEvent>,
    posts: PostMap,
    reg: ThresholdRegistry,
): List<TravelFlag> = impossibleTravel(
    events = events,
    posts = posts,
    vMaxKmh = reg[ThresholdName.VMAX],
    maxFlags = reg[ThresholdName.TRAVEL_MAX_FLAGS].toInt(),
)

/**
 * Great-circle distance in kilometres (haversine).
 *
 * [EARTH_MEAN_RADIUS_KM] is the IUGG mean radius, a physical constant rather than a tunable —
 * it is not an operating point and putting it in the registry would let someone "tune" the
 * planet. The result is the angular distance; the haversine form is used instead of the
 * spherical law of cosines because the latter loses precision for the small (sub-kilometre)
 * distances where the interesting near-misses live.
 */
const val EARTH_MEAN_RADIUS_KM = 6371.0088

fun haversineKm(a: PostCoord, b: PostCoord): Double {
    val lat1 = Math.toRadians(a.latDeg)
    val lat2 = Math.toRadians(b.latDeg)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.lonDeg - a.lonDeg)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_MEAN_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Three decimals is well under the precision of any handheld GPS and keeps reports stable. */
private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
