package wallora.domain.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Core enumerations shared by the editor, rule engine and costing pipeline.
 * Kept Android-free so the calculation module and backend tooling can reuse them.
 */

enum class SurfaceType(val allowsCeilingPaint: Boolean = false) {
    INTERIOR_WALL,
    CEILING,
    EXTERIOR_WALL,
    ACCENT_WALL,
    DOOR,
    WINDOW_GRILL,
    WOODWORK,
    METALWORK;

    /** Which paint families are legal for this surface (rule-engine guard). */
    val allowedPaintTypes: Set<PaintType>
        get() = when (this) {
            INTERIOR_WALL, ACCENT_WALL -> setOf(
                PaintType.DISTEMPER, PaintType.EMULSION, PaintType.PREMIUM_EMULSION,
                PaintType.LUXURY_SHEEN, PaintType.TEXTURE
            )
            CEILING -> setOf(PaintType.DISTEMPER, PaintType.EMULSION, PaintType.PREMIUM_EMULSION)
            EXTERIOR_WALL -> setOf(
                PaintType.EXTERIOR, PaintType.WATERPROOF, PaintType.TEXTURE
            )
            DOOR, WOODWORK -> setOf(PaintType.ENAMEL)
            WINDOW_GRILL, METALWORK -> setOf(PaintType.ENAMEL, PaintType.METAL_PRIMER_TOPCOAT)
        }
}

enum class WallCondition { NEW, PREVIOUSLY_PAINTED, DAMP, CRACKED, PEELING, FUNGUS }

enum class PaintType {
    DISTEMPER, EMULSION, PREMIUM_EMULSION, LUXURY_SHEEN, TEXTURE,
    ENAMEL, WATERPROOF, EXTERIOR, ANTI_FUNGAL, METAL_PRIMER_TOPCOAT
}

enum class Finish { MATT, SATIN, GLOSS }

enum class Tier(val rank: Int) { ECONOMY(0), MID(1), PREMIUM(2), LUXURY(3) }

enum class OpeningType { SINGLE_DOOR, DOUBLE_DOOR, SLIDING_DOOR, MAIN_DOOR, SMALL_WINDOW, LARGE_WINDOW, BAY_WINDOW, VENTILATOR, ARCH, CUPBOARD, PILLAR, STAIRCASE, FIREPLACE, BALCONY_RAILING }

/** How much of an opening's area is deducted from paintable wall area. */
enum class DeductPolicy(val factor: Double) {
    /** Full opening area removed (default, most accurate for cost). */
    FULL(1.0),
    /** Keep frame/jamb area (deduct only the clear hole ≈ 90%). */
    FRAME_EXCLUDED(0.9),
    /** Keep skirting running beneath windows/doors (deduct 85%). */
    SKIRTING_KEPT(0.85),
    /** Keep cornice band at wall top (deduct 92%). */
    CORNICE_KEPT(0.92),
    /** Do not deduct anything (worst-case / conservative estimate). */
    NONE(0.0);
}

enum class EstimationMode { DIY, CONTRACTOR }

enum class LabourBasis { PER_SQFT, PER_DAY }

enum class PriceSourceKind { RETAILER_API, LICENSED_FEED, CROWDSOURCED, MANUAL_CURATED, USER_OVERRIDE }

/** Badges shown in UI: Live (<6h), Today (<24h), Stale (<7d), Manual. */
enum class Freshness { LIVE, TODAY, STALE, MANUAL }

enum class VocLevel { LOW, MED, HIGH }

/** Unit system toggle; the engine always computes in feet internally. */
enum class LengthUnit { FEET, METRES }

enum class Currency(val code: String, val symbol: String) { INR("INR", "₹"), USD("USD", "$") }

/** Immutable 2-D point in **feet** (editor works in feet, converts for display). */
data class Point2D(val x: Double, val y: Double) {
    fun distanceTo(other: Point2D): Double = hypot(other.x - x, other.y - y)
    fun equalsEps(other: Point2D, eps: Double = 1e-6) = abs(x - other.x) < eps && abs(y - other.y) < eps
}

/** A simple polygon outline (room) given as ordered vertices in feet. */
data class Polygon(val points: List<Point2D>) {
    init {
        require(points.size >= 3) { "A polygon needs at least 3 points" }
    }

    /** Shoelace area, always positive, in sq ft. */
    val areaSqFt: Double
        get() {
            var sum = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                sum += a.x * b.y - b.x * a.y
            }
            return abs(sum) / 2.0
        }

    /** Perimeter in feet (closing edge included). */
    val perimeterFt: Double
        get() = points.indices.sumOf { i ->
            points[i].distanceTo(points[(i + 1) % points.size])
        }

    /** Axis-aligned bounding box width/height, handy for templates & hit-testing. */
    val bounds: Pair<Double, Double>
        get() = (points.maxOf { it.x } - points.minOf { it.x }) to
                (points.maxOf { it.y } - points.minOf { it.y })

    companion object {
        fun rect(widthFt: Double, heightFt: Double, origin: Point2D = Point2D(0.0, 0.0)) =
            Polygon(
                listOf(
                    origin,
                    Point2D(origin.x + widthFt, origin.y),
                    Point2D(origin.x + widthFt, origin.y + heightFt),
                    Point2D(origin.x, origin.y + heightFt),
                )
            )

        /** L-shaped room example used by tests & templates. */
        fun lShape(w1: Double, h1: Double, cutW: Double, cutH: Double) = Polygon(
            listOf(
                Point2D(0.0, 0.0), Point2D(w1, 0.0), Point2D(w1, h1 - cutH),
                Point2D(w1 - cutW, h1 - cutH), Point2D(w1 - cutW, h1), Point2D(0.0, h1),
            )
        )
    }
}
