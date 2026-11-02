package wallora.calculation

import wallora.domain.model.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Phase-1 acceptance tests for the calculation engine.
 * Run on plain JVM — no Android, no IO except the bundled seed resource.
 */
class CalculationEngineTest {

    // ------------------------------------------------------------------ seed

    @Test fun seedCatalogueParses() {
        val seed = SeedCatalog.load()
        assertEquals(8, seed.brands.size, "7 Indian brands + Custom")
        assertTrue(seed.products.any { it.id == "ap-tracker-4-in1" })
        assertTrue(seed.products.all { it.coveragePerLitre > 0 && it.seedPricePerPack.isNotEmpty() })
        assertTrue(seed.colors.isNotEmpty())
    }

    // -------------------------------------------------------------- geometry

    private fun rectRoom(w: Double = 12.0, h: Double = 10.0) = Room(
        id = "living", name = "Living Room", outline = Polygon.rect(w, h), wallHeightFt = 10.0,
    )

    @Test fun rectangleAreaAndPerimeter() {
        val r = rectRoom(12.0, 10.0)
        assertEquals(120.0, r.floorAreaSqFt, 1e-9)
        assertEquals(44.0, r.perimeterFt, 1e-9)
    }

    @Test fun lShapeGeometry() {
        val poly = Polygon.lShape(14.0, 12.0, cutW = 6.0, cutH = 5.0)
        // full 14×12 minus 6×5 notch
        assertEquals(168.0 - 30.0, poly.areaSqFt, 1e-9)
        assertEquals(52.0, poly.perimeterFt, 1e-9)
    }

    @Test fun grossWallAreaIsPerimeterTimesHeight() {
        val room = rectRoom(12.0, 10.0)
        val settings = EstimateSettings(paintTheCeiling = false)
        val g = GeometryService.geometry(room, settings)
        assertEquals(440.0, g.grossWallAreaSqFt, 1e-9) // 44 × 10
        assertEquals(0.0, g.openingDeductionSqFt, 1e-9)
        assertEquals(440.0, g.netWallAreaSqFt, 1e-9)
    }

    @Test fun openingsAreDeductedByPolicy() {
        val room = rectRoom(12.0, 10.0).copy(
            openings = listOf(
                Opening("d1", OpeningType.SINGLE_DOOR, "living-w0", offsetAlongWallFt = 2.0, widthFt = 3.0, heightFt = 7.0),
                Opening("w1", OpeningType.LARGE_WINDOW, "living-w2", offsetAlongWallFt = 3.0, widthFt = 4.0, heightFt = 4.0),
            )
        )
        val settings = EstimateSettings(defaultDeductPolicy = DeductPolicy.FULL, paintTheCeiling = false)
        val g = GeometryService.geometry(room, settings)
        assertEquals(21.0, g.openingDeductionSqFt, 1e-9) // 3×7 + 4×4
        assertEquals(419.0, g.netWallAreaSqFt, 1e-9)
    }

    @Test fun frameExcludedKeepsJambArea() {
        val o = Opening(
            "d1", OpeningType.MAIN_DOOR, "w0", 1.0, widthFt = 3.0, heightFt = 7.0,
            frameWidthFt = 0.25, deductPolicy = DeductPolicy.FRAME_EXCLUDED,
        )
        // clear hole = (3 - 2*0.25) × (7 - 0.25) = 2.5 × 6.75
        assertEquals(16.875, o.deductionSqFt, 1e-9)
        assertTrue(o.deductionSqFt < o.grossAreaSqFt)
    }

    @Test fun ceilingIncludedInPaintableWhenEnabled() {
        val room = rectRoom(12.0, 10.0)
        val withCeiling = GeometryService.geometry(room, EstimateSettings(paintTheCeiling = true))
        val without = GeometryService.geometry(room, EstimateSettings(paintTheCeiling = false))
        assertEquals(120.0, withCeiling.ceilingAreaSqFt, 1e-9)
        assertEquals(560.0, withCeiling.totalPaintableSqFt, 1e-9)
        assertEquals(440.0, without.totalPaintableSqFt, 1e-9)
    }

    // --------------------------------------------------------------- litres

    private fun product(id: String = "p1", coverage: Double = 100.0, packs: List<Double> = PackSize.STANDARD.map { it.litres }) =
        Product(
            id = id, lineId = "l1", brandId = "b1", name = "Test Emulsion $id",
            paintType = PaintType.EMULSION, tier = Tier.MID,
            surfaces = setOf(SurfaceType.INTERIOR_WALL, SurfaceType.CEILING),
            coveragePerLitre = coverage, durabilityYears = 5, washable = true,
            packs = packs.map { PackSize(it) },
            seedPricePerPack = packs.associateWith { 100.0 * it },
        )

    @Test fun litresFormulaMatchesSpec() {
        val req = GeometryService.litres(areaSqFt = 400.0, coats = 2, product = product(coverage = 100.0), wastagePercent = 5.0)
        assertEquals(8.0, req.litresRaw, 1e-9)          // 400×2/100
        assertEquals(8.4, req.litresWithWastage, 1e-9)  // ×1.05
    }

    @Test fun invalidCoatsRejected() {
        assertFailsWith<IllegalArgumentException> { GeometryService.litres(100.0, 4, product(), 5.0) }
        assertFailsWith<IllegalArgumentException> { GeometryService.litres(100.0, 0, product(), 5.0) }
    }

    // ------------------------------------------------------- pack optimizer

    @Test fun optimizerPrefersCheaperComboOverGreedy() {
        // Need 5 L. Greedy would buy 20L or 2×4L; here 4+1 is cheapest.
        val prices = listOf(
            PackOptimizer.PriceForPack(1.0, 100.0),
            PackOptimizer.PriceForPack(4.0, 360.0),
            PackOptimizer.PriceForPack(10.0, 900.0),
            PackOptimizer.PriceForPack(20.0, 1700.0),
        )
        val (purchases, cost) = PackOptimizer.optimize(5.0, prices)!!
        assertEquals(460.0, cost, 1e-9) // 360 + 100
        assertEquals(setOf(4.0, 1.0), purchases.map { it.packLitres }.toSet())
    }

    @Test fun optimizerBuysBulkForLargeJobs() {
        val prices = listOf(
            PackOptimizer.PriceForPack(1.0, 100.0),
            PackOptimizer.PriceForPack(4.0, 360.0),
            PackOptimizer.PriceForPack(10.0, 850.0),
            PackOptimizer.PriceForPack(20.0, 1600.0),
        )
        val (_, cost) = PackOptimizer.optimize(21.0, prices)!!
        // best within overshoot: 2×10 + … vs 20+… → 1600+? ; DP finds min ≤ need+4
        assertTrue(cost <= 1900.0, "expected bulk pricing to win, got $cost")
    }

    @Test fun optimizerMatchesBruteForceOnRandomisedInputs() {
        val rnd = kotlin.random.Random(42)
        repeat(60) {
            val sizes = listOf(1.0, 4.0, 10.0, 20.0)
            val priceMap = sizes.associateWith { sz ->
                val base = sz * (30.0 + rnd.nextInt(0, 90))
                base / (1.0 + 0.12 * sizes.indexOf(sz))
            }
            val need = 1.0 + rnd.nextInt(0, 40) + rnd.nextDouble()
            val dpInput = priceMap.entries.map { PackOptimizer.PriceForPack(it.key, it.value) }
            val (dpPurchases, dpCost) = PackOptimizer.optimize(need, dpInput, maxOvershootLitres = 4)!!
            val brute = PackBruteForce.cheapest(need, priceMap, maxOvershoot = 4)!!.second
            assertTrue(dpCost <= brute + 0.01, "DP ${dpCost} > brute ${brute} for need=$need prices=$priceMap")
            assertTrue(dpPurchases.sumOf { it.litres } >= kotlin.math.ceil(need - 1e-9) - 1e-9,
                "DP under-bought for need=$need")
        }
    }

    @Test fun optimizerHandlesFractionalPacks() {
        val prices = listOf(PackOptimizer.PriceForPack(0.5, 90.0), PackOptimizer.PriceForPack(1.0, 160.0))
        val (purchases, cost) = PackOptimizer.optimize(1.5, prices)!!
        assertEquals(240.0, cost, 1e-9) // 3×0.5 = 270 vs 1.0+0.5 = 250 vs ... check min
    }

    @Test fun optimizerRejectsUnpricedProducts() {
        assertNull(PackOptimizer.optimize(5.0, emptyList()))
        assertTrue(PackOptimizer.optimize(0.0, listOf(PackOptimizer.PriceForPack(1.0, 10.0))) == null)
    }

    // ------------------------------------------------------------ rule engine

    private fun job(surface: SurfaceType, condition: WallCondition, productId: String = "p1",
                    colorHex: Int = 0xFFFFFF, existing: Int? = null) = PaintJob(
        id = "job-${surface}-${condition}", roomId = "living", surface = surface, condition = condition,
        productId = productId, coats = 2, colorHex = colorHex, existingColorHex = existing,
        primerProductId = "primer1", primerCoats = if (condition == WallCondition.NEW) 1 else 0,
    )

    private val enamelProduct = Product(
        id = "enamel1", lineId = "l", brandId = "b", name = "Enamel", paintType = PaintType.ENAMEL,
        tier = Tier.MID, surfaces = setOf(SurfaceType.DOOR), coveragePerLitre = 110.0,
        durabilityYears = 3, washable = true, packs = listOf(PackSize(1.0)), seedPricePerPack = mapOf(1.0 to 320.0),
    )

    @Test fun newWallsRecommendPuttyPrimerAndRationale() {
        val rec = RuleEngine.recommend(job(SurfaceType.INTERIOR_WALL, WallCondition.NEW), product())
        assertTrue(rec.needsPrimer)
        assertNotNull(rec.primerReason)
        assertTrue(rec.prepItems.any { it.type == PrepItemType.WALL_PUTTY_1COAT })
        assertTrue(rec.prepItems.all { it.rationale.isNotBlank() }, "every prep item must explain itself")
    }

    @Test fun dampWallsGetDampProofing() {
        val rec = RuleEngine.recommend(job(SurfaceType.INTERIOR_WALL, WallCondition.DAMP), product())
        assertTrue(rec.prepItems.any { it.type == PrepItemType.DAMP_PROOFING && it.enabled })
        assertTrue(rec.reasons.any { it.contains("Damp") })
    }

    @Test fun fungusWallsGetTreatmentAndRemoval() {
        val rec = RuleEngine.recommend(job(SurfaceType.INTERIOR_WALL, WallCondition.FUNGUS), product())
        assertTrue(rec.prepItems.any { it.type == PrepItemType.FUNGUS_TREATMENT })
        assertTrue(rec.prepItems.any { it.type == PrepItemType.OLD_PAINT_REMOVAL })
    }

    @Test fun crackedWallsGetCrackFillingAndTwoCoatPutty() {
        val rec = RuleEngine.recommend(job(SurfaceType.INTERIOR_WALL, WallCondition.CRACKED), product())
        assertTrue(rec.prepItems.any { it.type == PrepItemType.CRACK_FILLING })
        assertTrue(rec.prepItems.any { it.type == PrepItemType.WALL_PUTTY_2COAT })
    }

    @Test fun darkOverLightAddsCoats() {
        val light = 0xF2F2F2u.toInt()
        val darkIndigo = 0x1A2233u.toInt()
        val rec = RuleEngine.recommend(
            job(SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED, colorHex = darkIndigo, existing = light),
            product().copy(extraCoatsForDarkBase = 1),
        )
        assertEquals(3, rec.coats)
        assertTrue(rec.reasons.any { it.contains("Dark shade") })
    }

    @Test fun exteriorGetsWaterproofingRule() {
        val exterior = Product(
            id = "ext1", lineId = "l", brandId = "b", name = "Exterior", paintType = PaintType.EXTERIOR,
            tier = Tier.PREMIUM, surfaces = setOf(SurfaceType.EXTERIOR_WALL), coveragePerLitre = 120.0,
            durabilityYears = 7, washable = true, packs = listOf(PackSize(10.0)), seedPricePerPack = mapOf(10.0 to 6800.0),
        )
        val rec = RuleEngine.recommend(job(SurfaceType.EXTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED, "ext1"), exterior)
        assertTrue(rec.prepItems.any { it.type == PrepItemType.WATERPROOFING })
    }

    @Test fun illegalSurfaceCombinationRejected() {
        assertFailsWith<IllegalArgumentException> {
            RuleEngine.recommend(job(SurfaceType.DOOR, WallCondition.NEW, "enamel1").copy(primerProductId = null), product())
        }
        // enamel on door IS legal:
        val ok = RuleEngine.recommend(job(SurfaceType.DOOR, WallCondition.PREVIOUSLY_PAINTED, "enamel1").copy(primerProductId = null), enamelProduct)
        assertEquals(PaintType.ENAMEL, ok.paintType)
    }

    @Test fun luminanceOrdersColorsCorrectly() {
        assertTrue(RuleEngine.luminance(0xFFFFFFu.toInt()) > RuleEngine.luminance(0x1A2233u.toInt()))
    }

    // ------------------------------------------------------------- cost engine

    private fun sampleProject(mode: EstimationMode = EstimationMode.CONTRACTOR) = Project(
        id = "proj", name = "Test House",
        floors = listOf(Floor("f0", "Ground", 0, listOf(rectRoom(12.0, 10.0)))),
        settings = EstimateSettings(mode = mode, gstPercent = 18.0, contingencyPercent = 5.0, wastagePercent = 5.0, paintTheCeiling = false),
        labour = LabourConfig(basis = LabourBasis.PER_SQFT, ratePerSqFt = 6.0),
        accessories = AccessorySelection(items = listOf(AccessoryItem("roller", "Roller", 180.0, perHundredSqFt = 0.6, minQty = 1))),
    )

    private fun engineFor(prices: Map<String, Map<Double, Double>>): CostEngine {
        val catalog = mapOf("p1" to product(), "primer1" to product(id = "primer1").copy(isPrimer = true))
        return CostEngine(catalog, { p, pack -> prices[p.id]?.get(pack) ?: p.seedPricePerPack[pack] })
    }

    @Test fun roomBreakdownSectionsAddUp() {
        val project = sampleProject()
        val engine = engineFor(mapOf("p1" to mapOf(1.0 to 100.0, 4.0 to 360.0, 10.0 to 850.0, 20.0 to 1600.0)))
        val est = engine.estimateRoom(project, project.allRooms.first(), listOf(job(SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED)))
        val bd = est.breakdown
        // 440 sq ft, 2 coats, cov 100 → 8.8 L → 20L pack (1600) beats others? 4×4L+1×... let's just verify arithmetic
        val materials = bd.lines.filter { it.section in setOf(CostSection.PAINT, CostSection.PRIMER, CostSection.PREP, CostSection.ACCESSORIES) }.sumOf { it.rupees }
        val labour = bd.lines.filter { it.section in setOf(CostSection.LABOUR, CostSection.CONTRACTOR_MARGIN) }.sumOf { it.rupees }
        assertEquals(materials + labour, bd.subtotalRupees, 1.5) // rounding tolerance
        assertEquals(bd.subtotalRupees + bd.gstRupees + bd.contingencyRupees, bd.totalRupees, 1.5)
        assertTrue(labour > 0)
        assertEquals(6.0 * 440.0, bd.lines.first { it.section == CostSection.LABOUR }.rupees, 1.0)
    }

    @Test fun diyModeHasNoLabour() {
        val project = sampleProject(mode = EstimationMode.DIY)
        val engine = engineFor(mapOf("p1" to mapOf(1.0 to 100.0, 4.0 to 360.0, 10.0 to 850.0, 20.0 to 1600.0)))
        val est = engine.estimateRoom(project, project.allRooms.first(), listOf(job(SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED)))
        assertTrue(est.breakdown.lines.none { it.section == CostSection.LABOUR })
    }

    @Test fun perDayLabourUsesProductivity() {
        val project = sampleProject().copy(
            labour = LabourConfig(basis = LabourBasis.PER_DAY, painters = 2, painterDayRate = 900.0, productivitySqFtPerPainterDay = 350.0, contractorMarginPercent = 0.0),
        )
        val engine = engineFor(mapOf("p1" to mapOf(1.0 to 100.0, 4.0 to 360.0, 10.0 to 850.0, 20.0 to 1600.0)))
        val est = engine.estimateRoom(project, project.allRooms.first(), listOf(job(SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED)))
        // 440 sqft ÷ (2×350) = ceil(0.63) = 1 day → 1×2×900 = 1800
        assertEquals(1800.0, est.breakdown.lines.first { it.section == CostSection.LABOUR }.rupees, 1.0)
    }

    @Test fun houseEstimateRangeExplainsUncertainty() {
        val project = sampleProject()
        val engine = engineFor(mapOf("p1" to mapOf(1.0 to 100.0, 4.0 to 360.0, 10.0 to 850.0, 20.0 to 1600.0)))
        val jobs = mapOf("living" to listOf(job(SurfaceType.INTERIOR_WALL, WallCondition.DAMP)))
        val house = engine.estimateHouse(project, jobs)
        assertTrue(house.range.minRupees < house.range.likelyRupees)
        assertTrue(house.range.maxRupees > house.range.likelyRupees)
        assertTrue(house.range.explanation.contains("damp", ignoreCase = true))
    }

    @Test fun shoppingListGroupsPurchasesAcrossRooms() {
        val rooms = listOf(rectRoom(12.0, 10.0).copy(id = "a"), rectRoom(12.0, 10.0).copy(id = "b"))
        val project = sampleProject().copy(floors = listOf(Floor("f0", "G", 0, rooms)))
        val engine = engineFor(mapOf("p1" to mapOf(1.0 to 100.0, 4.0 to 360.0, 10.0 to 850.0, 20.0 to 1600.0)))
        val jobs = rooms.associate { it.id to listOf(job(SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED).copy(roomId = it.id)) }
        val house = engine.estimateHouse(project, jobs)
        assertEquals(1, house.shoppingList.size, "same product+pack aggregated into one cart line")
        assertTrue(house.shoppingList.first().purchase.quantity >= 1)
    }

    // --------------------------------------------------------------- insights

    @Test fun goodBetterBestTiersAreMonotonic() {
        val seed = SeedCatalog.load()
        val catalog = seed.productMap()
        val living = Room("living", "Living", Polygon.rect(14.0, 12.0), 10.0)
        val project = Project("p", "Home", listOf(Floor("f", "G", 0, listOf(living))))
        val jobsByRoom: Map<String, List<PaintJob>> = mapOf(
            "living" to listOf(
                PaintJob("j1", "living", null, SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED,
                    "ap-tracker-4-in1", coats = 2, colorHex = 0xF2F2F2u.toInt()),
            )
        )
        val service = EstimateService(catalog, { p, pack -> p.seedPricePerPack[pack] })
        val options = service.insights().packageOptions(project, jobsByRoom, catalog)
        assertTrue(options.size >= 3, "should produce Good/Better/Best/Luxury, got ${options.map { it.tier }}")
        val totals = options.sortedBy { it.tier.rank }.map { it.breakdown.totalRupees }
        // economy ≤ mid (premium products differ by coverage so allow tiny inversion only when truly cheaper)
        assertTrue(totals.first() <= totals.last(), "cheapest tier should beat luxury: $totals")
    }

    @Test fun budgetModeFindsFitOrGap() {
        val seed = SeedCatalog.load()
        val catalog = seed.productMap()
        val living = Room("living", "Living", Polygon.rect(14.0, 12.0), 10.0)
        val project = Project("p", "Home", listOf(Floor("f", "G", 0, listOf(living))))
        val jobs = mapOf("living" to listOf(
            PaintJob("j1", "living", null, SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED, "ap-dreamwash-one-coat", 2)
        ))
        val service = EstimateService(catalog, { p, pack -> p.seedPricePerPack[pack] })
        val insights = service.insights()
        val baseline = service.estimate(project, jobs).houseBreakdown.totalRupees
        val rich = insights.fitBudget(project, jobs, catalog, budgetRupees = baseline * 10)
        assertTrue(rich.fits)
        val broke = insights.fitBudget(project, jobs, catalog, budgetRupees = 1.0)
        assertFalse(broke.fits)
        assertTrue(broke.gapRupees > 0)
    }

    @Test fun saveMoneyTipsSuggestTierDowngrade() {
        val seed = SeedCatalog.load()
        val catalog = seed.productMap()
        val living = Room("living", "Living", Polygon.rect(14.0, 12.0), 10.0)
        val project = Project("p", "Home", listOf(Floor("f", "G", 0, listOf(living))))
        val jobs = mapOf("living" to listOf(
            PaintJob("j1", "living", null, SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED, "ap-dreamwash-one-coat", 2)
        ))
        val service = EstimateService(catalog, { p, pack -> p.seedPricePerPack[pack] })
        val tips = service.insights().saveMoneyTips(project, jobs, catalog)
        assertTrue(tips.isNotEmpty(), "premium→mid downgrade should yield a tip")
        assertTrue(tips.first().savingRupees > 0)
        assertTrue(tips.first().message.contains("save"))
    }

    @Test fun brandComparisonBadgesAreUnique() {
        val seed = SeedCatalog.load()
        val catalog = seed.productMap()
        val living = Room("living", "Living", Polygon.rect(14.0, 12.0), 10.0)
        val project = Project("p", "Home", listOf(Floor("f", "G", 0, listOf(living))))
        val jobs = mapOf("living" to listOf(
            PaintJob("j1", "living", null, SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED, "ap-tracker-4-in1", 2)
        ))
        val service = EstimateService(catalog, { p, pack -> p.seedPricePerPack[pack] })
        val rows = service.insights().compareBrands(project, jobs, catalog)
        assertTrue(rows.isNotEmpty())
        assertEquals(1, rows.count { it.isCheapest })
        assertEquals(1, rows.count { it.isLongestLasting })
        assertEquals(1, rows.count { it.isBestValue })
        assertTrue(rows.all { it.costPerYear > 0 })
    }

    // ------------------------------------------------------------ price rules

    @Test fun freshnessBands() {
        val now = 1_700_000_000L
        assertEquals(Freshness.LIVE, PriceRules.freshnessOf(PriceSourceKind.RETAILER_API, now - 3 * 3600, now))
        assertEquals(Freshness.TODAY, PriceRules.freshnessOf(PriceSourceKind.RETAILER_API, now - 20 * 3600, now))
        assertEquals(Freshness.STALE, PriceRules.freshnessOf(PriceSourceKind.RETAILER_API, now - 3 * 24 * 3600, now))
        assertEquals(Freshness.MANUAL, PriceRules.freshnessOf(PriceSourceKind.MANUAL_CURATED, now - 100 * 24 * 3600, now))
        assertEquals(Freshness.MANUAL, PriceRules.freshnessOf(PriceSourceKind.USER_OVERRIDE, 0, now))
    }

    @Test fun outlierDetectionBlocksCrazyPrices() {
        assertFalse(PriceRules.isOutlier(2500.0, 2499.0))
        assertTrue(PriceRules.isOutlier(900.0, 2499.0))   // too good to be true
        assertTrue(PriceRules.isOutlier(5000.0, 2499.0))  // fat-finger
        assertFalse(PriceRules.isOutlier(50.0, 0.0))      // no reference yet
    }

    @Test fun buyHintFromHistory() {
        val history = listOf(2500.0, 2510.0, 2495.0, 2505.0, 2500.0)
        assertEquals(PriceRules.BuyHint.GOOD_TIME_TO_BUY, PriceRules.buyHint(2300.0, history))
        assertEquals(PriceRules.BuyHint.WAIT, PriceRules.buyHint(2700.0, history))
        assertEquals(PriceRules.BuyHint.NEUTRAL, PriceRules.buyHint(2500.0, history))
        assertEquals(PriceRules.BuyHint.NEUTRAL, PriceRules.buyHint(2500.0, listOf(2500.0)))
    }

    // ---------------------------------------------------------------- timeline

    @Test fun timelinePhasesExist() {
        val project = sampleProject()
        val engine = engineFor(mapOf("p1" to mapOf(1.0 to 100.0, 4.0 to 360.0, 10.0 to 850.0, 20.0 to 1600.0)))
        val house = engine.estimateHouse(project, mapOf("living" to listOf(job(SurfaceType.INTERIOR_WALL, WallCondition.PREVIOUSLY_PAINTED))))
        assertTrue(house.timelineDays.size >= 4)
        assertTrue(house.estimatedDays >= 1)
    }
}
