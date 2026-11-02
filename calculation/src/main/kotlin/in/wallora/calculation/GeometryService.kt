package in.wallora.calculation

import in.wallora.domain.model.*
import kotlin.math.ceil

/**
 * [1] GeometryService — turns editor polygons into paintable areas.
 * Pure functions, deterministic, fully unit-tested.
 */
object GeometryService {

    fun wallsOf(room: Room): List<WallSegment> = room.outline.points.indices.map { i ->
        val a = room.outline.points[i]
        val b = room.outline.points[(i + 1) % room.outline.points.size]
        WallSegment(id = "${room.id}-w$i", start = a, end = b, heightFt = room.wallHeightFt)
    }

    fun grossWallAreaSqFt(room: Room): Double =
        wallsOf(room).sumOf { it.lengthFt * room.wallHeightFt }

    /** Σ opening area × policy factor (FRAME_EXCLUDED uses clear-hole geometry). */
    fun openingDeductionSqFt(room: Room, fallbackPolicy: DeductPolicy): Double =
        room.openings.sumOf { o ->
            val policy = if (o.deductPolicy == DeductPolicy.FULL && fallbackPolicy != DeductPolicy.FULL) fallbackPolicy else o.deductPolicy
            o.copy(deductPolicy = policy).deductionSqFt
        }

    fun geometry(room: Room, settings: EstimateSettings): RoomGeometry {
        val gross = grossWallAreaSqFt(room)
        val deduction = openingDeductionSqFt(room, settings.defaultDeductPolicy)
        val ceiling = if (settings.paintTheCeiling) room.floorAreaSqFt else 0.0
        return RoomGeometry(
            roomId = room.id,
            roomName = room.name,
            floorAreaSqFt = room.floorAreaSqFt,
            grossWallAreaSqFt = gross,
            openingDeductionSqFt = deduction,
            netWallAreaSqFt = (gross - deduction).coerceAtLeast(0.0),
            ceilingAreaSqFt = ceiling,
        )
    }

    /**
     * [Litres] formula:
     * litresRaw = area × coats ÷ coverage ; withWastage = raw × (1 + wastage%)
     */
    fun litres(areaSqFt: Double, coats: Int, product: Product, wastagePercent: Double): LitresRequirement {
        require(coats in 1..3) { "Coats must be between 1 and 3" }
        require(product.coveragePerLitre > 0) { "Coverage must be positive" }
        val raw = areaSqFt * coats / product.coveragePerLitre
        return LitresRequirement(
            product = product,
            areaSqFt = round2(areaSqFt),
            coats = coats,
            wastagePercent = wastagePercent,
            litresRaw = round2(raw),
            litresWithWastage = round2(raw * (1.0 + wastagePercent / 100.0)),
        )
    }

    fun daysForPainting(totalSqFt: Double, labour: LabourConfig): Int {
        val perDay = labour.painters.coerceAtLeast(1) * labour.productivitySqFtPerPainterDay
        if (perDay <= 0.0) return 1
        return ceil(totalSqFt / perDay).toInt().coerceAtLeast(1)
    }

    private fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
}
