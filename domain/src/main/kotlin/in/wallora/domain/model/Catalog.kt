package in.wallora.domain.model

/**
 * Catalogue + live-price entities. The app only ever sees normalised offers
 * from our backend (or the bundled seed) — never retailer data directly.
 */

data class Brand(
    val id: String,
    val name: String,
    val country: String = "IN",
    /** Affiliate tag applied by the backend to product URLs (disclosed in UI). */
    val affiliateTag: String? = null,
)

data class ProductLine(
    val id: String,
    val brandId: String,
    val name: String,
    val tier: Tier,
    val paintType: PaintType,
    val surfaces: Set<SurfaceType>,
    val description: String = "",
)

data class PackSize(val litres: Double, val label: String = "${litres} L") {
    companion object {
        val STANDARD = listOf(PackSize(1.0, "1 L"), PackSize(4.0, "4 L"), PackSize(10.0, "10 L"), PackSize(20.0, "20 L"))
    }
}

data class Product(
    val id: String,
    val lineId: String,
    val brandId: String,
    val name: String,
    val paintType: PaintType,
    val tier: Tier,
    val surfaces: Set<SurfaceType>,
    /** Sq ft per litre per coat at normal application. */
    val coveragePerLitre: Double,
    val durabilityYears: Int,
    val washable: Boolean,
    val vocLevel: VocLevel = VocLevel.LOW,
    val minTempCureC: Double = 10.0,
    /** Extra coats typically needed when painting a dark shade over light base. */
    val extraCoatsForDarkBase: Int = 1,
    val packs: List<PackSize> = PackSize.STANDARD,
    /** Admin-curated fallback price per pack (₹), used until live data arrives. */
    val seedPricePerPack: Map<Double, Double> = emptyMap(),
    val isPrimer: Boolean = false,
)

data class Retailer(
    val id: String,
    val name: String,
    val homepageUrl: String,
    /** Terms under which we may show this retailer's prices (see README). */
    val sourceKind: PriceSourceKind,
    val supportsPincode: Boolean = true,
)

/** Normalised offer as returned by our backend for one product+pack+retailer(+region). */
data class RetailerOffer(
    val productId: String,
    val retailerId: String,
    val packLitres: Double,
    val priceRupees: Double,
    val productUrl: String,
    val inStock: Boolean,
    val observedAtEpochSec: Long,
    val confidence: Double,
    val sourceKind: PriceSourceKind,
    val pincodePrefix: String? = null,
)

data class PriceSnapshot(
    val productId: String,
    val packLitres: Double,
    val retailerId: String,
    val rupees: Double,
    val capturedAtEpochSec: Long,
)

data class PriceHistory(
    val productId: String,
    val packLitres: Double,
    val retailerId: String,
    val points: List<PriceSnapshot>,
)

/** User-submitted price ("I bought X at Y for Z"); validated before promotion. */
data class UserPriceSubmission(
    val id: String,
    val productId: String,
    val retailerId: String,
    val packLitres: Double,
    val claimedRupees: Double,
    val city: String?,
    val hasBillPhoto: Boolean,
    val ocrExtractedRupees: Double? = null,
    val upvotes: Int = 0,
    val status: SubmissionStatus = SubmissionStatus.PENDING,
)

enum class SubmissionStatus { PENDING, VALIDATED, REJECTED_OUTLIER, APPROVED }

/** A user override always wins over live data; reset restores resolution order. */
data class PriceOverride(val productId: String, val packLitres: Double, val rupeesPerPack: Double)

data class Quote(
    val id: String,
    val projectId: String,
    val version: Int,
    val title: String,
    val totalRupees: Double,
    val generatedAtEpochSec: Long,
    val whiteLabelContractorName: String? = null,
)

data class ProjectVersion(
    val projectId: String,
    val version: Int,
    val snapshotJson: String,
    val createdAtEpochSec: Long,
    val label: String = "v$version",
)
