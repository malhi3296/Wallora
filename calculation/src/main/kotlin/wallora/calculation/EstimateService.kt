package wallora.calculation

import wallora.domain.model.*

/**
 * Bundles the pure engine behind one façade so ViewModels (Phase 2+) and tests
 * share identical wiring. [priceResolver] is supplied by the repository layer:
 * override → live offer → seed fallback, exactly that precedence.
 */
class EstimateService(
    private val catalog: Map<String, Product>,
    private val priceResolver: (Product, Double) -> Double?,
    private val offerInfo: (productId: String, packLitres: Double) -> CostEngine.OfferMeta = { _, _ -> CostEngine.OfferMeta.NONE },
) {
    fun newEngine(): CostEngine = CostEngine(catalog, priceResolver, offerInfo)

    fun estimate(project: Project, jobsByRoom: Map<String, List<PaintJob>>): HouseEstimate =
        newEngine().estimateHouse(project, jobsByRoom)

    fun insights(): Insights = Insights(::newEngine)
}
