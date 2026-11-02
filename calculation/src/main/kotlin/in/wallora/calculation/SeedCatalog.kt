package in.wallora.calculation

import in.wallora.domain.model.*

/**
 * Loads the bundled seed catalogue (admin-curated fallback prices) without any
 * JSON library — a tiny hand-rolled parser keeps :calculation dependency-free.
 * Phase 4 replaces the source with Room-cached backend data; the shape is identical.
 */
data class SeedCatalog(
    val brands: List<Brand>,
    val retailers: List<Retailer>,
    val products: List<Product>,
    val colors: List<ColorShade>,
) {
    fun productMap(): Map<String, Product> = products.associateBy { it.id }

    companion object {
        /** Reads /seed/brands.json from the domain module's resources. */
        fun load(resourcePath: String = "/seed/brands.json"): SeedCatalog {
            val text = SeedCatalog::class.java.getResourceAsStream(resourcePath)?.readBytes()?.decodeToString()
                ?: error("Seed catalogue missing at $resourcePath")
            return SeedJsonParser.parse(text)
        }
    }
}
