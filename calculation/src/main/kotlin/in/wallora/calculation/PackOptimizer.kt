package in.wallora.calculation

import in.wallora.domain.model.*

/**
 * [4] PackOptimizer — cheapest combination of pack sizes for exactly the litres
 * needed (including wastage). Unbounded integer knapsack solved by DP over whole
 * litres with a bounded overshoot window, so 20L-heavy requirements stay cheap
 * and small jobs never buy a drum.
 *
 * Why not greedy "largest pack first"? Because e.g. needing 5 L: greedy buys 1×20L,
 * but 1×4L + 1×1L can be cheaper. The DP explores all combinations up to
 * [maxOvershootLitres] beyond the requirement.
 */
object PackOptimizer {

    data class PriceForPack(val packLitres: Double, val rupees: Double)

    /** @return purchases + totals; null if no priced pack exists. */
    fun optimize(
        litresNeeded: Double,
        packPrices: List<PriceForPack>,
        maxOvershootLitres: Int = 4,
    ): Pair<List<PackPurchase>, Double>? {
        val usable = packPrices.filter { it.rupees > 0 && it.packLitres > 0 }
        if (usable.isEmpty() || litresNeeded <= 0.0) return null

        // Work in whole litres (ceil requirement) to keep DP finite & exact enough.
        val need = kotlin.math.ceil(litresNeeded - 1e-9).toInt().coerceAtLeast(1)
        val limit = need + maxOvershootLitres

        // Scale fractional packs (e.g. 0.5 L primers) to integers ×scale.
        val scale = denominatorScale(usable.map { it.packLitres })
        val packsScaled = usable.map { (it.packLitres * scale).toInt() to it }
        val needScaled = need * scale
        val limitScaled = limit * scale

        val inf = Double.MAX_VALUE / 2
        val dp = DoubleArray(limitScaled + 1) { if (it == 0) 0.0 else inf }
        // parent[v] = pack index chosen last to reach v (with count reconstruction)
        val take = IntArray(limitScaled + 1) { -1 }
        for (v in 1..limitScaled) {
            for ((idx, pair) in packsScaled.withIndex()) {
                val (size, price) = pair
                if (size in 1..v && dp[v - size] + price.rupees < dp[v]) {
                    dp[v] = dp[v - size] + price.rupees
                    take[v] = idx
                }
            }
        }

        // best total anywhere in [needScaled .. limitScaled]
        var bestV = -1
        var bestCost = inf
        for (v in needScaled..limitScaled) {
            if (dp[v] < bestCost) { bestCost = dp[v]; bestV = v }
        }
        if (bestV < 0 || bestCost >= inf) return null

        // Reconstruct counts
        val counts = HashMap<Int, Int>()
        var cur = bestV
        while (cur > 0) {
            val idx = take[cur]
            require(idx >= 0) { "DP inconsistency at $cur" }
            counts[idx] = (counts[idx] ?: 0) + 1
            cur -= packsScaled[idx].first
        }

        val purchases = counts.entries.sortedBy { it.key }.map { (idx, qty) ->
            val (_, price) = packsScaled[idx]
            PackPurchase(price.packLitres, qty, price.rupees)
        }
        return purchases to bestCost
    }

    fun plan(requirement: LitresRequirement, packPrices: List<PriceForPack>): PackPlan? {
        val (purchases, cost) = optimize(requirement.litresWithWastage, packPrices) ?: return null
        val bought = purchases.sumOf { it.litres }
        return PackPlan(
            requirement = requirement,
            purchases = purchases,
            totalLitresBought = bought,
            totalRupees = round2(cost),
            surplusLitres = round2(bought - requirement.litresWithWastage),
        )
    }

    /** Smallest power-of-ish scale making all litre values integral (handles 0.5/0.25 L). */
    private fun denominatorScale(values: List<Double>): Int {
        var scale = 1
        repeat(4) { // supports up to .25 L granularity
            if (values.all { v -> (v * scale).let { kotlin.math.abs(it - kotlin.math.round(it)) < 1e-6 } }) return scale
            scale *= 2
        }
        return scale
    }

    private fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
}
