package wallora.domain.model

/** Paint job attached to a room surface (or a single wall for accent walls). */
data class PaintJob(
    val id: String,
    val roomId: String,
    /** null = whole room's surfaces of [surface]; set for per-wall/accent jobs. */
    val wallSegmentId: String? = null,
    val surface: SurfaceType,
    val condition: WallCondition,
    val productId: String,
    val coats: Int = 2,
    val finish: Finish = Finish.MATT,
    val colorHex: Int = 0xFFFFFF,
    /** Hex of the colour currently on the wall; drives extra-coat rules. */
    val existingColorHex: Int? = null,
    val primerProductId: String? = null,
    val primerCoats: Int = 0,
)

/** Per-project costing switches. Defaults tuned for the Indian market. */
data class EstimateSettings(
    val currency: Currency = Currency.INR,
    val lengthUnit: LengthUnit = LengthUnit.FEET,
    val wastagePercent: Double = 5.0,
    val gstPercent: Double = 18.0,
    val contingencyPercent: Double = 5.0,
    val defaultDeductPolicy: DeductPolicy = DeductPolicy.FULL,
    val paintTheCeiling: Boolean = true,
    val mode: EstimationMode = EstimationMode.CONTRACTOR,
    val roundingToRupees: Boolean = true,
    val preferredRetailerIds: Set<String> = emptySet(), // empty = all retailers
    val wifiOnlySync: Boolean = false,
)

enum class PrepItemType {
    SCRAPING_SANDING, OLD_PAINT_REMOVAL, CRACK_FILLING, WALL_PUTTY_1COAT, WALL_PUTTY_2COAT,
    PRIMER, DAMP_PROOFING, WATERPROOFING, FUNGUS_TREATMENT, TEXTURE_BASE,
    FURNITURE_MOVING_COVERING
}

/** A preparation line item; rate is ₹ per sq ft of applicable area. */
data class PrepItem(
    val type: PrepItemType,
    val label: String,
    val ratePerSqFt: Double,
    val enabled: Boolean,
    /** Human-readable reason shown in the "why is this recommended?" tooltip. */
    val rationale: String,
    /** 'walls' | 'ceiling' | 'all' — which areas this applies to. */
    val appliesTo: String = "walls",
    val autoRecommended: Boolean = false,
)

data class AccessoryItem(
    val id: String,
    val name: String,
    val unitPrice: Double,
    /** How many units are needed per 100 sq ft painted (0 ⇒ manual qty only). */
    val perHundredSqFt: Double,
    val minQty: Int = 0,
    val enabled: Boolean = true,
    val manualOverrideQty: Int? = null,
)

object DefaultAccessories {
    val list = listOf(
        AccessoryItem("brush_2in", "2-inch brush", 90.0, perHundredSqFt = 0.4, minQty = 1),
        AccessoryItem("roller", "Paint roller + sleeve", 180.0, perHundredSqFt = 0.6, minQty = 1),
        AccessoryItem("tray", "Paint tray", 70.0, perHundredSqFt = 0.2, minQty = 1),
        AccessoryItem("tape", "Masking tape (roll)", 60.0, perHundredSqFt = 0.8, minQty = 1),
        AccessoryItem("dropsheet", "Drop sheet (per room)", 120.0, perHundredSqFt = 0.3, minQty = 1),
        AccessoryItem("sandpaper", "Sandpaper sheets", 10.0, perHundredSqFt = 3.0, minQty = 5),
        AccessoryItem("thinner", "Thinner / water bucket", 150.0, perHundredSqFt = 0.1, minQty = 0),
        AccessoryItem("puttyknife", "Putty knife", 120.0, perHundredSqFt = 0.1, minQty = 0),
        AccessoryItem("ladder", "Scaffolding/ladder rental (per day)", 350.0, perHundredSqFt = 0.0, minQty = 0, enabled = false),
    )
}

data class AccessorySelection(val items: List<AccessoryItem> = DefaultAccessories.list)

data class LabourConfig(
    val basis: LabourBasis = LabourBasis.PER_SQFT,
    /** ₹/sq ft for full painting job (interior emulsion, 2 coats). */
    val ratePerSqFt: Double = 6.0,
    val painterDayRate: Double = 900.0,
    val painters: Int = 2,
    /** Sq ft one painter completes per working day (prep+paint, interior). */
    val productivitySqFtPerPainterDay: Double = 350.0,
    val regionalPresetId: String = "metro-in",
    val contractorMarginPercent: Double = 12.0,
)

data class ColorShade(
    val id: String,
    val brandId: String,
    val name: String,
    val hex: Int,
    val code: String,
    /** 0..1 — how dark the shade is; dark bases over light walls may need extra coats. */
    val luminanceWeight: Double = 0.5,
    val trending: Boolean = false,
)
