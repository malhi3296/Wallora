package wallora.calculation

import wallora.domain.model.*
import kotlin.math.ceil
import kotlin.math.round

/**
 * [5] CostEngine — the deterministic, transparent costing pipeline.
 *
 * Inputs:  Project (geometry + paint jobs) and a synchronous price resolver
 *          `product -> packLitres -> rupees` (fed by live offers / overrides / seed).
 * Output:  HouseEstimate with per-room breakdowns, shopping list, range, timeline.
 *
 * The engine is pure: no clocks, no IO, no Android. Time-sensitive freshness is
 * resolved by the caller inside [priceResolver].
 */
class CostEngine(
    private val catalog: Map<String, Product>,
    /** Effective ₹ for one pack of a product, or null if unpriced. */
    private val priceResolver: (product: Product, packLitres: Double) -> Double?,
    /** Optional richer info per chosen purchase (retailer/url/freshness). */
    private val offerInfo: (productId: String, packLitres: Double) -> OfferMeta = { _, _ -> OfferMeta.NONE },
) {

    data class OfferMeta(
        val retailerId: String, val retailerName: String, val buyUrl: String,
        val freshness: Freshness, val confidence: Double,
    ) {
        companion object {
            val NONE = OfferMeta("seed", "Wallora curated", "", Freshness.MANUAL, 0.7)
        }
    }

    // ------------------------------------------------------------------ rooms

    fun estimateRoom(project: Project, room: Room, jobs: List<PaintJob>): RoomEstimate {
        val settings = project.settings
        val geometry = GeometryService.geometry(room, settings)

        val litresList = mutableListOf<LitresRequirement>()
        val plans = mutableListOf<PackPlan>()
        val prepItems = mutableListOf<PrepItem>()
        val lines = mutableListOf<CostLine>()

        var wallAreaForPrep = geometry.netWallAreaSqFt
        var ceilingAreaForPrep = geometry.ceilingAreaSqFt

        for (job in jobs) {
            val product = catalog[job.productId]
                ?: error("Unknown product ${job.productId} — check catalogue sync")
            require(job.surface.allowedPaintTypes.contains(product.paintType)) {
                "${product.name} (${product.paintType}) is not valid on ${job.surface}"
            }

            val area = if (job.surface == SurfaceType.CEILING) geometry.ceilingAreaSqFt
                       else geometry.netWallAreaSqFt
            if (area <= 0.0) continue

            val rec = RuleEngine.recommend(job, product)
            val req = GeometryService.litres(area, rec.coats, product, settings.wastagePercent)
            litresList += req

            val packPrices = product.packs.mapNotNull { p ->
                priceResolver(product, p.litres)?.let { PackOptimizer.PriceForPack(p.litres, it) }
            }
            val plan = PackOptimizer.plan(req, packPrices)
            if (plan != null) {
                plans += plan
                lines += CostLine("${product.name} · ${job.surface}", CostSection.PAINT, plan.totalRupees,
                    detail = req.formulaText() + " → packs: " + plan.purchases.joinToString { "${it.quantity}×${it.packLitres}L" })
            }

            if (rec.needsPrimer && job.primerProductId != null) {
                val primer = catalog[job.primerProductId!!]
                if (primer != null) {
                    val primerReq = GeometryService.litres(area, job.primerCoats.coerceIn(1, 2), primer, settings.wastagePercent)
                    val primerPrices = primer.packs.mapNotNull { p ->
                        priceResolver(primer, p.litres)?.let { PackOptimizer.PriceForPack(p.litres, it) }
                    }
                    PackOptimizer.plan(primerReq, primerPrices)?.let {
                        plans += it
                        lines += CostLine("${primer.name} (primer)", CostSection.PRIMER, it.totalRupees,
                            detail = primerReq.formulaText())
                    }
                }
            }

            prepItems += rec.prepItems
        }

        // --- Prep cost -----------------------------------------------------
        val mergedPrep = mergePrep(prepItems)
        for (item in mergedPrep.filter { it.enabled }) {
            val applicable = when (item.appliesTo) {
                "ceiling" -> ceilingAreaForPrep
                "all" -> wallAreaForPrep + ceilingAreaForPrep
                else -> wallAreaForPrep
            }
            if (applicable <= 0.0) continue
            val cost = item.ratePerSqFt * applicable
            lines += CostLine(item.label, CostSection.PREP, round2(cost),
                detail = "₹${item.ratePerSqFt}/sq ft × %.0f sq ft — ${item.rationale}".format(applicable))
        }

        // --- Accessories ----------------------------------------------------
        val totalPaintable = geometry.totalPaintableSqFt
        accessoriesCost(project.accessories.items, totalPaintable, lines)

        // --- Labour & margin --------------------------------------------------
        lines += labourLines(project, totalPaintable)

        // --- Taxes ------------------------------------------------------------
        val materials = lines.filter { it.section in setOf(CostSection.PAINT, CostSection.PRIMER, CostSection.PREP, CostSection.ACCESSORIES) }.sumOf { it.rupees }
        val labourTotal = lines.filter { it.section in setOf(CostSection.LABOUR, CostSection.CONTRACTOR_MARGIN) }.sumOf { it.rupees }
        val subtotal = materials + labourTotal
        val gst = subtotal * settings.gstPercent / 100.0
        val contingency = materials * settings.contingencyPercent / 100.0
        if (settings.gstPercent > 0) lines += CostLine("GST", CostSection.GST, round2(gst), "${settings.gstPercent}%")
        if (settings.contingencyPercent > 0) lines += CostLine("Contingency", CostSection.CONTINGENCY, round2(contingency), "${settings.contingencyPercent}% of materials")

        val breakdown = buildBreakdown(lines, subtotal, gst, contingency, settings)
        return RoomEstimate(geometry, litresList, plans, mergedPrep, breakdown)
    }

    // ------------------------------------------------------------------ house

    fun estimateHouse(project: Project, jobsByRoom: Map<String, List<PaintJob>>): HouseEstimate {
        val rooms = project.allRooms.mapNotNull { room ->
            val jobs = jobsByRoom[room.id] ?: return@mapNotNull null
            estimateRoom(project, room, jobs)
        }

        val allLines = rooms.flatMap { it.breakdown.lines }
        val materials = allLines.filter { it.section in setOf(CostSection.PAINT, CostSection.PRIMER, CostSection.PREP, CostSection.ACCESSORIES) }.sumOf { it.rupees }
        val labour = allLines.filter { it.section in setOf(CostSection.LABOUR, CostSection.CONTRACTOR_MARGIN) }.sumOf { it.rupees }
        val subtotal = materials + labour
        val gst = subtotal * project.settings.gstPercent / 100.0
        val contingency = materials * project.settings.contingencyPercent / 100.0
        val houseBreakdown = buildBreakdown(allLines, subtotal, gst, contingency, project.settings)

        val shopping = shoppingList(rooms, project)
        val totalLitres = rooms.sumOf { r -> r.packPlans.sumOf { it.totalLitresBought } }
        val paintable = rooms.sumOf { it.geometry.totalPaintableSqFt }
        val days = GeometryService.daysForPainting(paintable, project.labour)

        val uncertainty = uncertaintyPercent(project, jobsByRoom)
        val range = EstimateRange(
            minRupees = round2(houseBreakdown.totalRupees * (1 - uncertainty)),
            likelyRupees = round2(houseBreakdown.totalRupees),
            maxRupees = round2(houseBreakdown.totalRupees * (1 + uncertainty)),
            explanation = uncertaintyExplanation(project, jobsByRoom, uncertainty),
        )

        val timeline = buildTimeline(project, days)

        return HouseEstimate(rooms, houseBreakdown, shopping, range, round2(totalLitres), days, timeline)
    }

    // ------------------------------------------------------------- internals

    private fun buildBreakdown(lines: List<CostLine>, subtotal: Double, gst: Double, contingency: Double, s: EstimateSettings): Breakdown {
        val total = subtotal + gst + contingency
        return Breakdown(
            lines = lines.map { it.copy(rupees = money(it.rupees, s)) },
            subtotalRupees = money(subtotal, s),
            gstRupees = money(gst, s),
            contingencyRupees = money(contingency, s),
            totalRupees = money(total, s),
        )
    }

    private fun money(v: Double, s: EstimateSettings) = if (s.roundingToRupees) round(v) else round2(v)

    private fun mergePrep(items: List<PrepItem>): List<PrepItem> {
        val map = LinkedHashMap<PrepItemType, PrepItem>()
        for (i in items) {
            val prev = map[i.type]
            map[i.type] = if (prev == null || (i.enabled && !prev.enabled)) i else prev
        }
        return map.values.toList()
    }

    private fun accessoriesCost(items: List<AccessoryItem>, totalSqFt: Double, out: MutableList<CostLine>): Double {
        var sum = 0.0
        for (a in items.filter { it.enabled }) {
            val autoQty = ceil(totalSqFt / 100.0 * a.perHundredSqFt).toInt()
            val qty = a.manualOverrideQty ?: maxOf(autoQty, a.minQty)
            if (qty <= 0) continue
            val cost = qty * a.unitPrice
            sum += cost
            out += CostLine(a.name, CostSection.ACCESSORIES, round2(cost), "${qty} × ₹${a.unitPrice}")
        }
        return sum
    }

    private fun labourLines(project: Project, totalSqFt: Double): List<CostLine> {
        if (project.settings.mode == EstimationMode.DIY) return emptyList()
        val l = project.labour
        val lines = mutableListOf<CostLine>()
        val labourCost = when (l.basis) {
            LabourBasis.PER_SQFT -> {
                val c = l.ratePerSqFt * totalSqFt
                lines += CostLine("Painter labour", CostSection.LABOUR, round2(c),
                    "₹${l.ratePerSqFt}/sq ft × %.0f sq ft".format(totalSqFt))
                c
            }
            LabourBasis.PER_DAY -> {
                val days = GeometryService.daysForPainting(totalSqFt, l)
                val c = days * l.painters * l.painterDayRate
                lines += CostLine("Painter labour", CostSection.LABOUR, round2(c),
                    "$days day(s) × ${l.painters} painter(s) × ₹${l.painterDayRate}/day")
                c
            }
        }
        if (l.contractorMarginPercent > 0) {
            lines += CostLine("Contractor margin", CostSection.CONTRACTOR_MARGIN, round2(labourCost * l.contractorMarginPercent / 100.0),
                "${l.contractorMarginPercent}% of labour")
        }
        return lines
    }

    private fun shoppingList(rooms: List<RoomEstimate>, project: Project): List<ShoppingListItem> {
        // Aggregate identical product+pack purchases across rooms, then group by retailer.
        val agg = LinkedHashMap<Pair<String, Double>, Int>()
        for (r in rooms) for (p in r.packPlans) for (buy in p.purchases) {
            val key = p.requirement.product.id to buy.packLitres
            agg[key] = (agg[key] ?: 0) + buy.quantity
        }
        return agg.entries.mapNotNull { (key, qty) ->
            val product = catalog[key.first] ?: return@mapNotNull null
            val meta = offerInfo(key.first, key.second)
            ShoppingListItem(
                productId = product.id, productName = product.name, brandName = product.brandId,
                retailerId = meta.retailerId, retailerName = meta.retailerName,
                purchase = PackPurchase(key.second, qty, priceResolver(product, key.second) ?: 0.0),
                buyUrl = meta.buyUrl, freshness = meta.freshness, confidence = meta.confidence,
            )
        }.sortedWith(compareBy({ it.retailerName }, { it.productName }))
    }

    private fun uncertaintyPercent(project: Project, jobs: Map<String, List<PaintJob>>): Double {
        var u = 0.04 // baseline measurement error
        val allJobs = jobs.values.flatten()
        if (allJobs.any { it.condition == WallCondition.DAMP || it.condition == WallCondition.CRACKED }) u += 0.12
        if (allJobs.any { it.existingColorHex == null && it.condition == WallCondition.PREVIOUSLY_PAINTED }) u += 0.08
        if (allJobs.any { it.surface == SurfaceType.EXTERIOR_WALL }) u += 0.10
        return u.coerceAtMost(0.30)
    }

    private fun uncertaintyExplanation(project: Project, jobs: Map<String, List<PaintJob>>, pct: Double): String {
        val reasons = mutableListOf("±4% base measurement tolerance")
        val all = jobs.values.flatten()
        if (all.any { it.condition == WallCondition.DAMP || it.condition == WallCondition.CRACKED })
            reasons += "+12% hidden substrate work once damp/cracks open up"
        if (all.any { it.existingColorHex == null && it.condition == WallCondition.PREVIOUSLY_PAINTED })
            reasons += "+8% unknown number of old coats under existing paint"
        if (all.any { it.surface == SurfaceType.EXTERIOR_WALL })
            reasons += "+10% exterior access/scaffolding variability"
        return "Range ${(pct * 100).toInt()}% wide because: " + reasons.joinToString("; ") + "."
    }

    private fun buildTimeline(project: Project, paintDays: Int): List<TimelinePhase> {
        val heavyPrep = project.settings.mode == EstimationMode.CONTRACTOR || project.allRooms.isNotEmpty()
        return listOf(
            TimelinePhase("Surface prep", if (heavyPrep) 2 else 1, "Scraping, crack filling, putty"),
            TimelinePhase("Putty drying", 1, "24 h cure before sanding/primer"),
            TimelinePhase("Primer", 1, "Seal + true colour base"),
            TimelinePhase("Topcoats", paintDays, "Includes drying between coats"),
            TimelinePhase("Touch-ups & cleaning", 1, "Edges, fixtures, handover"),
        )
    }

    private fun round2(v: Double) = round(v * 100) / 100.0
}
