package in.wallora.domain.model

/**
 * Pure helpers for freshness + confidence, shared by UI badges and the engine.
 * No coroutines dependency here — [Freshness] resolution is time-injected.
 */
object PriceRules {

    /** LIVE < 6h · TODAY < 24h · STALE < 7d · older than that = stale too; MANUAL for seeds/overrides. */
    fun freshnessOf(sourceKind: PriceSourceKind, observedAtEpochSec: Long, nowEpochSec: Long): Freshness {
        if (sourceKind == PriceSourceKind.MANUAL_CURATED || sourceKind == PriceSourceKind.USER_OVERRIDE) {
            return Freshness.MANUAL
        }
        val ageHours = (nowEpochSec - observedAtEpochSec) / 3600.0
        return when {
            ageHours < 6.0 -> Freshness.LIVE
            ageHours < 24.0 -> Freshness.TODAY
            else -> Freshness.STALE
        }
    }

    fun baseConfidence(sourceKind: PriceSourceKind): Double = when (sourceKind) {
        PriceSourceKind.RETAILER_API -> 0.95
        PriceSourceKind.LICENSED_FEED -> 0.80
        PriceSourceKind.CROWDSOURCED -> 0.60
        PriceSourceKind.MANUAL_CURATED -> 0.70
        PriceSourceKind.USER_OVERRIDE -> 1.0
    }

    /** Crowdsourcing guard: reject submissions far from the current median. */
    fun isOutlier(claimedRupees: Double, medianRupees: Double): Boolean {
        if (medianRupees <= 0.0) return false // no reference yet
        return claimedRupees < medianRupees * 0.6 || claimedRupees > medianRupees * 1.6
    }

    /** "Good time to buy / Wait" hint from 90-day history. */
    fun buyHint(currentRupees: Double, historyPoints: List<Double>): BuyHint {
        if (historyPoints.size < 5) return BuyHint.NEUTRAL
        val sorted = historyPoints.sorted()
        val median = sorted[sorted.size / 2]
        val ratio = currentRupees / median
        return when {
            ratio <= 0.95 -> BuyHint.GOOD_TIME_TO_BUY
            ratio >= 1.05 -> BuyHint.WAIT
            else -> BuyHint.NEUTRAL
        }
    }

    enum class BuyHint { GOOD_TIME_TO_BUY, NEUTRAL, WAIT }
}
