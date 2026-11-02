package wallora.calculation

import wallora.domain.model.*

/**
 * [6] Insights — save-money tips, Good/Better/Best packages, budget-mode solver
 * and brand comparison metrics. All operate on a re-runnable estimate closure so
 * numbers always come from the same deterministic engine (no parallel math).
 */
class Insights(private val engineFactory: () -> CostEngine) {

    /** Re-estimates the house with product substitutions applied to jobs. */
    fun estimateWith(
        project: Project,
        jobsByRoom: Map<String, List<PaintJob>>,
        substitute: (PaintJob) -> PaintJob,
    ): HouseEstimate = engineFactory().estimateHouse(project, jobsByRoom.mapValues { (_, jobs) -> jobs.map(substitute) })

    // ---------------------------------------------------------- save money

    fun saveMoneyTips(
        project: Project,
        jobsByRoom: Map<String, List<PaintJob>>,
        catalog: Map<String, Product>,
        maxTips: Int = 5,
    ): List<SaveMoneyTip> {
        val baseline = engineFactory().estimateHouse(project, jobsByRoom)
        val baseTotal = baseline.houseBreakdown.totalRupees
        val tips = mutableListOf<SaveMoneyTip>()

        // 1) Tier down each room one step where possible.
        for ((roomId, jobs) in jobsByRoom) {
            val room = project.allRooms.firstOrNull { it.id == roomId } ?: continue
            for (job in jobs.filter { it.surface == SurfaceType.INTERIOR_WALL }) {
                val product = catalog[job.productId] ?: continue
                val cheaper = catalog.values.firstOrNull {
                    it.paintType == product.paintType && it.tier.rank == product.tier.rank - 1 &&
                        SurfaceType.INTERIOR_WALL in it.surfaces
                } ?: continue
                val alt = jobsByRoom.toMutableMap().apply {
                    this[roomId] = jobs.map { if (it.id == job.id) it.copy(productId = cheaper.id) else it }
                }
                val newTotal = engineFactory().estimateHouse(project, alt).houseBreakdown.totalRupees
                val saving = baseTotal - newTotal
                if (saving > 100) {
                    tips += SaveMoneyTip(
                        "Switch ${room.name} to ${cheaper.name} (${cheaper.tier}) and save ₹%,.0f".format(saving),
                        saving, roomId,
                    )
                }
            }
        }

        // 2) DIY mode hint.
        if (project.settings.mode == EstimationMode.CONTRACTOR) {
            val diyProject = project.copy(settings = project.settings.copy(mode = EstimationMode.DIY))
            val diyTotal = engineFactory().estimateHouse(diyProject, jobsByRoom).houseBreakdown.totalRupees
            val saving = baseTotal - diyTotal
            if (saving > 500) tips += SaveMoneyTip("Do it yourself (skip labour) and save ₹%,.0f".format(saving), saving)
        }

        // 3) Turn off optional prep that isn't auto-recommended.
        val optionalPrepCost = baseline.rooms.flatMap { it.prepItems }.filter { !it.autoRecommended && it.enabled }
            .sumOf { it.ratePerSqFt } // rate-only signal; UI shows real number after toggle
        if (optionalPrepCost > 0) {
            tips += SaveMoneyTip("Optional extras like furniture covering can be toggled off if rooms are already empty.", 0.0)
        }

        return tips.sortedByDescending { it.savingRupees }.take(maxTips)
    }

    // ------------------------------------------------------- good/better/best

    fun packageOptions(
        project: Project,
        jobsByRoom: Map<String, List<PaintJob>>,
        catalog: Map<String, Product>,
    ): List<PackageOption> {
        val blurb = mapOf(
            Tier.ECONOMY to "Good — fresh look, shortest durability",
            Tier.MID to "Better — balanced washability & life",
            Tier.PREMIUM to "Best value — premium finish, long life",
            Tier.LUXURY to "Luxury — showroom-grade sheen & warranty",
        )
        return Tier.entries.mapNotNull { tier ->
            val substituted = jobsByRoom.mapValues { (_, jobs) ->
                jobs.map { job ->
                    val current = catalog[job.productId] ?: return@map job
                    if (current.isPrimer) return@map job
                    val candidate = catalog.values.filter {
                        it.paintType == current.paintType && it.tier == tier && job.surface in it.surfaces
                    }.minByOrNull { priceOf(it) } ?: return@map job
                    job.copy(productId = candidate.id)
                }
            }
            runCatching { engineFactory().estimateHouse(project, substituted) }.getOrNull()?.let { est ->
                PackageOption(tier.name, tier, est.houseBreakdown, blurb[tier] ?: "")
            }
        }.filter { it.breakdown.totalRupees > 0 }
    }

    private fun priceOf(p: Product): Double =
        p.seedPricePerPack.values.minOrNull() ?: Double.MAX_VALUE

    // ------------------------------------------------------------ budget mode

    data class BudgetResult(val fits: Boolean, val option: PackageOption?, val gapRupees: Double)

    /** Cheapest tier combination that fits the budget; otherwise the cheapest + gap. */
    fun fitBudget(project: Project, jobsByRoom: Map<String, List<PaintJob>>, catalog: Map<String, Product>, budgetRupees: Double): BudgetResult {
        val options = packageOptions(project, jobsByRoom, catalog).sortedBy { it.breakdown.totalRupees }
        val fitting = options.firstOrNull { it.breakdown.totalRupees <= budgetRupees }
        return if (fitting != null) BudgetResult(true, fitting, budgetRupees - fitting.breakdown.totalRupees)
        else BudgetResult(false, options.firstOrNull(), options.firstOrNull()?.let { it.breakdown.totalRupees - budgetRupees } ?: 0.0)
    }

    // ------------------------------------------------------ brand comparison

    data class BrandComparisonRow(
        val brandId: String,
        val totalRupees: Double,
        val litres: Double,
        val avgDurabilityYears: Double,
        val costPerYear: Double,
        val isCheapest: Boolean,
        val isLongestLasting: Boolean,
        val isBestValue: Boolean,
    )

    fun compareBrands(
        project: Project,
        jobsByRoom: Map<String, List<PaintJob>>,
        catalog: Map<String, Product>,
    ): List<BrandComparisonRow> {
        val brandIds = catalog.values.map { it.brandId }.distinct()
        val rows = brandIds.mapNotNull { brand ->
            // A brand row is only valid if EVERY non-primer job can be served by
            // that brand with the same paint type & surface. Otherwise skip —
            // a partial substitution would silently mislead the comparison.
            var complete = true
            val substituted = jobsByRoom.mapValues { (_, jobs) ->
                jobs.map { job ->
                    val current = catalog[job.productId] ?: return@map job
                    if (current.isPrimer) return@map job
                    val candidate = catalog.values.filter {
                        it.brandId == brand && it.paintType == current.paintType &&
                            !it.isPrimer && job.surface in it.surfaces
                    }.minByOrNull { priceOf(it) }
                    if (candidate == null) { complete = false; return@map job }
                    job.copy(productId = candidate.id)
                }
            }
            if (!complete) return@mapNotNull null
            val est = runCatching { engineFactory().estimateHouse(project, substituted) }.getOrNull() ?: return@mapNotNull null
            val durabilities = substituted.values.flatten().mapNotNull { catalog[it.productId]?.durabilityYears }
            val avgDur = if (durabilities.isEmpty()) 1.0 else durabilities.average()
            BrandComparisonRow(
                brandId = brand,
                totalRupees = est.houseBreakdown.totalRupees,
                litres = est.totalLitres,
                avgDurabilityYears = avgDur,
                costPerYear = est.houseBreakdown.totalRupees / avgDur.coerceAtLeast(1.0),
                isCheapest = false, isLongestLasting = false, isBestValue = false,
            )
        }
        if (rows.isEmpty()) return rows
        val cheapest = rows.minByOrNull { it.totalRupees }?.brandId
        val longest = rows.maxByOrNull { it.avgDurabilityYears }?.brandId
        val bestValue = rows.minByOrNull { it.costPerYear }?.brandId
        return rows.map {
            it.copy(
                isCheapest = it.brandId == cheapest,
                isLongestLasting = it.brandId == longest,
                isBestValue = it.brandId == bestValue,
            )
        }.sortedBy { it.totalRupees }
    }
}
