package in.wallora.domain.model

/** Result types produced by the calculation engine (kept Android-free). */

data class RoomGeometry(
    val roomId: String,
    val roomName: String,
    val floorAreaSqFt: Double,
    val grossWallAreaSqFt: Double,
    val openingDeductionSqFt: Double,
    val netWallAreaSqFt: Double,
    val ceilingAreaSqFt: Double,
) {
    /** Everything a painter must actually cover for this room. */
    val totalPaintableSqFt: Double get() = netWallAreaSqFt + ceilingAreaSqFt
}

enum class CostSection { PAINT, PRIMER, PREP, ACCESSORIES, LABOUR, CONTRACTOR_MARGIN, GST, CONTINGENCY }

data class CostLine(
    val label: String,
    val section: CostSection,
    val rupees: Double,
    val detail: String = "",
)

data class Breakdown(
    val lines: List<CostLine>,
    val subtotalRupees: Double,
    val gstRupees: Double,
    val contingencyRupees: Double,
    val totalRupees: Double,
)

data class LitresRequirement(
    val product: Product,
    val areaSqFt: Double,
    val coats: Int,
    val wastagePercent: Double,
    val litresRaw: Double,
    val litresWithWastage: Double,
) {
    /** Formula string surfaced in "How is this calculated?". */
    fun formulaText(): String =
        "$areaSqFt sq ft × $coats coats ÷ ${product.coveragePerLitre} sq ft/L × (1 + ${(wastagePercent / 100)}) = %.2f L".format(litresWithWastage)
}

data class PackPurchase(val packLitres: Double, val quantity: Int, val unitPriceRupees: Double) {
    val totalRupees: Double get() = quantity * unitPriceRupees
    val litres: Double get() = quantity * packLitres
}

data class PackPlan(
    val requirement: LitresRequirement,
    val purchases: List<PackPurchase>,
    val totalLitresBought: Double,
    val totalRupees: Double,
    val surplusLitres: Double,
)

data class ShoppingListItem(
    val productId: String,
    val productName: String,
    val brandName: String,
    val retailerId: String,
    val retailerName: String,
    val purchase: PackPurchase,
    val buyUrl: String,
    val freshness: Freshness,
    val confidence: Double,
)

data class EstimateRange(val minRupees: Double, val likelyRupees: Double, val maxRupees: Double, val explanation: String)

data class SaveMoneyTip(val message: String, val savingRupees: Double, val actionRoomId: String? = null)

data class PackageOption(val name: String, val tier: Tier, val breakdown: Breakdown, val blurb: String)

data class RoomEstimate(
    val geometry: RoomGeometry,
    val litres: List<LitresRequirement>,
    val packPlans: List<PackPlan>,
    val prepItems: List<PrepItem>,
    val breakdown: Breakdown,
)

data class HouseEstimate(
    val rooms: List<RoomEstimate>,
    val houseBreakdown: Breakdown,
    val shoppingList: List<ShoppingListItem>,
    val range: EstimateRange,
    val totalLitres: Double,
    val estimatedDays: Int,
    val timelineDays: List<TimelinePhase>,
)

data class TimelinePhase(val name: String, val days: Int, val note: String = "")
