package wallora.calculation

import wallora.domain.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * [2] RuleEngine — smart recommendations from (surface, condition, colour change):
 *  • legal + recommended paint type & tier for the surface
 *  • number of coats (incl. dark-over-light extra coat)
 *  • whether a primer is required and which
 *  • prep items with human-readable rationale ("why is this recommended?")
 * Every recommendation carries its reason so the UI can be fully transparent.
 */
object RuleEngine {

    data class Recommendation(
        val paintType: PaintType,
        val minTier: Tier,
        val coats: Int,
        val needsPrimer: Boolean,
        val primerReason: String?,
        val prepItems: List<PrepItem>,
        val reasons: List<String>,
    )

    /** Relative luminance of an RGB int (0..1). */
    fun luminance(colorHex: Int): Double {
        val r = (colorHex shr 16 and 0xFF) / 255.0
        val g = (colorHex shr 8 and 0xFF) / 255.0
        val b = (colorHex and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun recommend(job: PaintJob, product: Product): Recommendation {
        val reasons = mutableListOf<String>()
        var coats = job.coats.coerceIn(1, 3)
        var needsPrimer = job.primerCoats > 0
        var primerReason: String? = null

        // --- Surface legality -------------------------------------------------
        require(job.surface.allowedPaintTypes.contains(product.paintType)) {
            "${product.paintType} cannot be used on ${job.surface}"
        }

        // --- Colour-change rule: dark new colour over light existing wall ----
        val existing = job.existingColorHex
        if (existing != null) {
            val delta = luminance(existing) - luminance(job.colorHex)
            if (delta > DARK_OVER_LIGHT_THRESHOLD) {
                val extra = product.extraCoatsForDarkBase.coerceAtLeast(1)
                val before = coats
                coats = min(coats + extra, 3)
                if (coats > before) {
                    reasons += "Dark shade over a lighter existing wall: +${coats - before} coat(s) for full hiding."
                }
            }
        }

        // --- Condition-driven rules ------------------------------------------
        val prep = mutableListOf<PrepItem>()
        when (job.condition) {
            WallCondition.NEW -> {
                needsPrimer = true
                primerReason = "New plaster/putty absorbs paint; a primer seals the surface and gives true colour."
                prep += putty("A fresh surface needs a smooth base before painting.", coats = 1)
                prep += PrepItem(PrepItemType.PRIMER, "Wall primer", 4.0, enabled = true,
                    rationale = primerReason!!, appliesTo = "walls", autoRecommended = true)
                reasons += "New walls: putty + primer recommended."
            }
            WallCondition.PREVIOUSLY_PAINTED -> {
                prep += PrepItem(PrepItemType.SCRAPING_SANDING, "Light sanding", 2.0, enabled = true,
                    rationale = "Deglosses and cleans the old film so new paint adheres properly.",
                    autoRecommended = true)
                reasons += "Previously painted: sanding only; primer optional unless changing sheen dramatically."
            }
            WallCondition.DAMP -> {
                needsPrimer = true
                primerReason = "Damp-primer blocks residual moisture from bleeding through the topcoat."
                prep += PrepItem(PrepItemType.DAMP_PROOFING, "Damp-proofing treatment", 22.0, enabled = true,
                    rationale = "Stops moisture ingress; without it new paint will peel again within months.",
                    autoRecommended = true)
                prep += PrepItem(PrepItemType.OLD_PAINT_REMOVAL, "Remove flaking paint", 8.0, enabled = true,
                    rationale = "Loose layers must go back to a solid substrate before any treatment.",
                    autoRecommended = true)
                coats = max(coats, 2)
                reasons += "Damp detected: damp-proofing + removal are mandatory for durability."
            }
            WallCondition.CRACKED -> {
                needsPrimer = true
                primerReason = "Primer after crack-filling evens out porosity of patched areas."
                prep += PrepItem(PrepItemType.CRACK_FILLING, "Crack filling (putty + mesh)", 9.0, enabled = true,
                    rationale = "Bridges cracks so they don't telegraph through the fresh coat.",
                    autoRecommended = true)
                prep += putty("Whole-wall putty levels minor undulations around patches.", coats = 2)
                reasons += "Cracks: filling + 2-coat putty prevents repeat cracking."
            }
            WallCondition.PEELING -> {
                needsPrimer = true
                primerReason = "Fresh primer grips the bare exposed substrate after stripping."
                prep += PrepItem(PrepItemType.OLD_PAINT_REMOVAL, "Strip peeling paint", 10.0, enabled = true,
                    rationale = "Paint cannot bond to lifting film; strip to a firm edge.",
                    autoRecommended = true)
                prep += PrepItem(PrepItemType.SCRAPING_SANDING, "Sanding to key", 3.0, enabled = true,
                    rationale = "Creates mechanical grip for the primer on stripped areas.",
                    autoRecommended = true)
                reasons += "Peeling: full strip + sand + primer is the reliable fix."
            }
            WallCondition.FUNGUS -> {
                needsPrimer = true
                primerReason = "Anti-fungal primer keeps spores dormant under the topcoat."
                prep += PrepItem(PrepItemType.FUNGUS_TREATMENT, "Fungus/algae treatment", 18.0, enabled = true,
                    rationale = "Kills live spores; painting over them regrows stains in weeks.",
                    autoRecommended = true)
                prep += PrepItem(PrepItemType.OLD_PAINT_REMOVAL, "Remove infected paint", 9.0, enabled = true,
                    rationale = "Spores live inside the porous old film; it must be removed.",
                    autoRecommended = true)
                reasons += "Fungus: treatment is not optional — otherwise stains bleed through."
            }
        }

        if (job.surface == SurfaceType.EXTERIOR_WALL) {
            prep += PrepItem(PrepItemType.WATERPROOFING, "Exterior waterproofing coat", 25.0, enabled = true,
                rationale = "Exteriors face rain UV load; waterproofing doubles the repaint interval.",
                autoRecommended = true)
            reasons += "Exterior wall: waterproofing recommended."
        }

        // Furniture moving only for interior jobs (cheap, default off for DIY).
        if (job.surface == SurfaceType.INTERIOR_WALL || job.surface == SurfaceType.ACCENT_WALL) {
            prep += PrepItem(PrepItemType.FURNITURE_MOVING_COVERING, "Furniture shifting & covering", 1.5,
                enabled = false, rationale = "Protects floors/furniture; skip if the room is already empty.",
                autoRecommended = false)
        }

        val minTier = when (job.surface) {
            SurfaceType.EXTERIOR_WALL -> Tier.MID
            SurfaceType.INTERIOR_WALL, SurfaceType.ACCENT_WALL -> Tier.ECONOMY
            else -> Tier.ECONOMY
        }

        return Recommendation(
            paintType = product.paintType,
            minTier = minTier,
            coats = coats,
            needsPrimer = needsPrimer,
            primerReason = primerReason,
            prepItems = prep,
            reasons = reasons,
        )
    }

    private fun putty(reason: String, coats: Int) = PrepItem(
        type = if (coats >= 2) PrepItemType.WALL_PUTTY_2COAT else PrepItemType.WALL_PUTTY_1COAT,
        label = "Wall putty (${coats} coat${if (coats > 1) "s" else ""})",
        ratePerSqFt = if (coats >= 2) 12.0 else 7.0,
        enabled = true,
        rationale = reason,
        autoRecommended = true,
    )

    private const val DARK_OVER_LIGHT_THRESHOLD = 0.25

    /** Colour impact note surfaced in the visualiser ("this colour costs more"). */
    fun colorImpact(existingHex: Int?, newHex: Int, product: Product): String? {
        if (existingHex == null) return null
        val delta = luminance(existingHex) - luminance(newHex)
        return if (delta > DARK_OVER_LIGHT_THRESHOLD) {
            "Deep colour over a light wall needs ~${product.extraCoatsForDarkBase} extra coat — adds roughly " +
                "%.0f%% to paint quantity.".format(product.extraCoatsForDarkBase * 50.0)
        } else null
    }
}
