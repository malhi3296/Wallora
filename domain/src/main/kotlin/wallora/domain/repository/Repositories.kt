package wallora.domain.repository

import wallora.domain.model.*

/**
 * Repository interfaces (Clean Architecture boundary).
 * Implementations live in :data (Room/DataStore/Retrofit) from Phase 2 onward;
 * the engine depends only on these, so it is fully testable with fakes.
 *
 * NOTE: deliberately synchronous — the calculation engine and its tests run on a
 * plain JVM without kotlinx-coroutines. The Android layer wraps these in
 * suspend functions / Flows inside :data.
 */

interface CatalogRepository {
    fun brands(): List<Brand>
    fun productLines(brandId: String? = null): List<ProductLine>
    fun products(surface: SurfaceType?, tier: Tier?): List<Product>
    fun productById(id: String): Product?
    fun colors(brandId: String? = null): List<ColorShade>
}

/** Normalised price access. The app never calls retailers directly. */
interface PriceRepository {
    /** Best offers per (product, pack) honouring overrides, pincode & retailer prefs. */
    fun bestOffers(productIds: Set<String>, pincode: String?): Map<String, List<RetailerOffer>>

    /** Effective ₹/pack after applying user override → live offer → seed fallback. */
    fun effectivePrice(product: Product, packLitres: Double, pincode: String?): PricedPack?

    fun history(productId: String, packLitres: Double, days: Int): PriceHistory?
    fun setOverride(productId: String, packLitres: Double, rupees: Double)
    fun clearOverride(productId: String, packLitres: Double)
    fun submitUserPrice(submission: UserPriceSubmission): Result<Unit>
}

data class PricedPack(
    val product: Product,
    val pack: PackSize,
    val rupees: Double,
    val sourceKind: PriceSourceKind,
    val freshness: Freshness,
    val confidence: Double,
    val retailerId: String? = null,
    val buyUrl: String? = null,
)

enum class SyncState { IDLE, RUNNING, SUCCESS, FAILED_USING_CACHE }

interface ProjectRepository {
    fun projects(includeArchived: Boolean = false): List<Project>
    fun project(id: String): Project?
    fun save(project: Project)
    fun delete(id: String)
}

interface SettingsRepository {
    fun estimateSettings(): EstimateSettings
    fun setEstimateSettings(settings: EstimateSettings)
}
