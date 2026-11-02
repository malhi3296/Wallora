package in.wallora.domain.repository

import in.wallora.domain.model.*

/**
 * Repository interfaces (Clean Architecture boundary).
 * Implementations live in :data (Room/DataStore/Retrofit) from Phase 2 onward;
 * the engine depends only on these, so it is fully testable with fakes.
 */

interface CatalogRepository {
    suspend fun brands(): List<Brand>
    suspend fun productLines(brandId: String? = null): List<ProductLine>
    suspend fun products(surface: SurfaceType?, tier: Tier?): List<Product>
    suspend fun productById(id: String): Product?
    suspend fun colors(brandId: String? = null): List<ColorShade>
}

/** Normalised price access. The app never calls retailers directly. */
interface PriceRepository {
    /** Best offers per (product, pack) honouring overrides, pincode & retailer prefs. */
    suspend fun bestOffers(productIds: Set<String>, pincode: String?): Map<String, List<RetailerOffer>>

    /** Effective ₹/pack after applying user override → live offer → seed fallback. */
    suspend fun effectivePrice(product: Product, packLitres: Double, pincode: String?): PricedPack?

    suspend fun history(productId: String, packLitres: Double, days: Int): PriceHistory?
    suspend fun setOverride(productId: String, packLitres: Double, rupees: Double)
    suspend fun clearOverride(productId: String, packLitres: Double)
    suspend fun submitUserPrice(submission: UserPriceSubmission): Result<Unit>
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
    suspend fun projects(includeArchived: Boolean = false): List<Project>
    suspend fun project(id: String): Project?
    suspend fun save(project: Project)
    suspend fun delete(id: String)
}

interface SettingsRepository {
    suspend fun estimateSettings(): EstimateSettings
    suspend fun setEstimateSettings(settings: EstimateSettings)
}
