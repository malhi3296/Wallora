package wallora.domain.model

/**
 * Geometry entities for the floor-plan editor (Phase 2) — defined now so the
 * calculation engine can already consume real plans.
 */

/** One straight wall between two vertices of a room outline. */
data class WallSegment(
    val id: String,
    val start: Point2D,
    val end: Point2D,
    /** Which side faces the room interior; used to tint walls with paint colour. */
    val heightFt: Double = 10.0,
    val isExterior: Boolean = false,
) {
    val lengthFt: Double get() = start.distanceTo(end)
    val grossAreaSqFt: Double get() = lengthFt * heightFt
}

/** A door/window/other element sitting on a wall segment. */
data class Opening(
    val id: String,
    val type: OpeningType,
    val wallId: String,
    /** Distance along the wall from its start vertex, feet. */
    val offsetAlongWallFt: Double,
    val widthFt: Double,
    val heightFt: Double,
    /** Frame/jamb width kept when policy = FRAME_EXCLUDED, feet. */
    val frameWidthFt: Double = 0.25,
    val deductPolicy: DeductPolicy = DeductPolicy.FULL,
) {
    val grossAreaSqFt: Double get() = widthFt * heightFt

    /** Area actually removed from paintable wall area under this opening's policy. */
    val deductionSqFt: Double
        get() {
            val clearW = (widthFt - 2 * frameWidthFt).coerceAtLeast(0.0)
            val clearH = (heightFt - frameWidthFt).coerceAtLeast(0.0) // no frame at floor level
            return when (deductPolicy) {
                DeductPolicy.FRAME_EXCLUDED -> clearW * clearH
                else -> grossAreaSqFt * deductPolicy.factor
            }
        }
}

data class Room(
    val id: String,
    val name: String,
    val outline: Polygon,
    val wallHeightFt: Double = 10.0,
    val openings: List<Opening> = emptyList(),
    /** Painted per-wall colours keyed by wall segment id (colour visualiser). */
    val wallColors: Map<String, Int> = emptyMap(),
    val lockCeilingPainted: Boolean = true,
) {
    val floorAreaSqFt: Double get() = outline.areaSqFt
    val perimeterFt: Double get() = outline.perimeterFt
}

data class Floor(
    val id: String,
    val name: String,
    val index: Int,
    val rooms: List<Room>,
)

data class Project(
    val id: String,
    val name: String,
    val floors: List<Floor>,
    val settings: EstimateSettings = EstimateSettings(),
    val labour: LabourConfig = LabourConfig(),
    val accessories: AccessorySelection = AccessorySelection(),
    val pincode: String? = null,
    val city: String? = null,
    val archived: Boolean = false,
) {
    val allRooms: List<Room> get() = floors.flatMap { it.rooms }
}
