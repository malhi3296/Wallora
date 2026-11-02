# Wallora — Architecture Document

**Wallora** — *"Design your walls. Know your cost."*
Android app (`in.wallora.app`) for interactive floor-plan design + transparent painting estimates with **live paint prices**.

---

## 1. Guiding principles

| Principle | How we honour it |
|---|---|
| **Offline-first** | Room is the single source of truth. Live prices are a *cache overlay*, never a dependency. Sync failure ⇒ last-known prices + gentle banner. |
| **Transparent costing** | Every rupee in the summary traces back to a formula the UI can show ("How is this calculated?"). The engine is pure Kotlin, deterministic, unit-tested. |
| **The app never talks to retailers** | Only `api.wallora.in` (our backend) returns normalised offers. Retailer terms live server-side and in `backend/SOURCES.md`. |
| **Localisable** | All strings via resources; currency/units/locale are runtime settings, not compile-time constants. Default market: India (INR, sq ft). |
| **Testable core** | Domain + engine modules have **zero Android dependencies** → fast JVM tests, reusable by the backend later. |

---

## 2. Module graph

```
                    ┌──────────────┐
                    │     :app     │  Compose UI, Hilt wiring, navigation, themes
                    └──┬───────┬───┘
                       │       │
              ┌────────▼──┐ ┌──▼────────────┐
              │  :core    │ │ :data         │  Room DAOs, DataStore, Retrofit,
              │  (pure    │ │               │  WorkManager sync, repository impls
              │   JVM)    │ └──┬─────────┬───┘
              └────────┬──┘    │         │
              ┌────────▼───────┴──┐   ┌──▼──────────┐
              │ :domain           │   │ :network    │  Moshi DTOs + mapper
              │ models, repos IF, │   └─────────────┘
              │ rule engine       │
              └────────▲──────────┘
                       │
              ┌────────┴──────────┐
              │ :calculation      │  Cost engine, pack optimizer, coverage,
              │ (pure JVM)        │  prep rules, labour, budget solver
              └───────────────────┘
              :feature-editor (later phases) depends on :domain, :calculation
              :backend (Cloudflare Worker + D1) shares seed JSON + semantics
```

Dependency rule: arrows point inward only. `:calculation` and `:domain` are `java-library`-style Kotlin JVM modules (no `android.*` imports) so they build and test anywhere.

### Folder structure (as delivered)

```
wallora/
├── settings.gradle.kts            module registry + version catalog
├── gradle/libs.versions.toml      single source of truth for versions
├── build.gradle.kts               root, plugin versions
├── gradle.properties              memory / offline-friendly flags
├── docs/
│   ├── ARCHITECTURE.md            (this file)
│   └── PHASES.md                  phase plan + status
├── domain/src/main/kotlin/in/wallora/domain/model/     all entities (§ Data model)
├── calculation/src/main/kotlin/in/wallora/calculation/ engine (pure)
├── domain/src/main/resources/seed/brands.json          admin-curated fallback prices
├── app/src/main/…                 UI, DI, manifest, resources (phases 2-6)
├── data/ network/ feature-editor/                        scaffolds (phase 2+)
└── backend/                                             Cloudflare Worker + D1 + admin
```

---

## 3. Tech stack decisions

**Android**: Kotlin 2.x, Jetpack Compose + Material 3 (dynamic colour), MVVM + Clean Architecture, Hilt, Room, DataStore, Coroutines/Flow, WorkManager, Retrofit + Moshi + OkHttp (with `Cache`), CameraX + ML Kit (segmentation/OCR, optional ARCore), Coil. Min SDK 26, target latest stable.

**Backend**: **Cloudflare Workers + D1** chosen over Firebase because
1. price refresh is a simple cron — Workers Cron Triggers need no extra service,
2. D1 (SQLite) matches our read-heavy key/value-ish access and keeps cost ≈ $0,
3. the same SQL schema can be mirrored in the app's Room DB, and
4. no vendor lock-in for auth (Google Sign-In stays optional & client-side).

Firestore remains a drop-in alternative; only `backend/src/db.ts` would change.

---

## 4. Data model (Phase 1 — implemented)

All IDs are stable strings (`"ap-dreamwash-one-coat"`), so seeds, cloud rows and user overrides join without ambiguity. Money is stored as **rupees-with-two-decimals in `Long` cents-free form (`Double` in engine, integer paise in persistence)** — see §6.2.

```
Project ──< Floor ──< Room ──< WallSegment ──< Opening (door/window/arch…)
   │                    │           └── PaintJob (surface, product, coats, finish)
   │                    └── PaintJob (ceiling / accent)
   ├── EstimateSettings (wastage %, GST %, rounding, opening deduction policy)
   ├── LabourConfig (per-sq-ft or per-day, painters, regional preset)
   ├── AccessorySelection (consumables + toggles)
   └── PreparedFor: PrepPlan (PrepItem list w/ rates & rationale)

Catalogue: Brand ──< ProductLine ──< Product ──< PackSize
Price:     Product ← RetailerOffer ← PriceSnapshot ← PriceHistory
           UserPriceSubmission → (OCR bill, upvotes, outlier check) → approved offer
Extras:    ColorShade (brand-accurate RAL/Hex + tint strength), Quote, ProjectVersion
Geometry:  Point2D(x, y) in feet, Polygon(room outline), Edge(wall segment)
```

Key modelling notes
* **`SurfaceType`** = INTERIOR_WALL, CEILING, EXTERIOR_WALL, DOOR, WINDOW_GRILL, WOODWORK, METALWORK, ACCENT_WALL, FLOOR_TRIM — drives which product lines are legal.
* **`WallCondition`** = NEW, PREVIOUSLY_PAINTED, DAMP, CRACKED, PEELING, FUNGUS — drives the prep rule engine.
* **`Opening.deductPolicy`** = FULL / FRAME_EXCLUDED (deduct opening minus frame area) / SKIRTING_KEPT / CORNICE_KEPT — configurable per project.
* **`Product`** carries `coveragePerLitre`, `durabilityYears`, `washable`, `vocLevel`, `minTempCureC`, `extraCoatsForDarkBase` — everything the rule engine and "cost impact of colour" feature need.
* **`RetailerOffer.confidence`** ∈ [0,1] from source class: API feed 0.95, licensed data 0.8, crowdsourced+validated 0.6, admin seed 0.7 flagged `MANUAL_CURATED`.

---

## 5. Calculation pipeline (the heart)

```
FloorPlan geometry                Catalogue + Prices (+overrides)
        │                                  │
        ▼                                  ▼
[1] GeometryService          [3] PriceRepository.bestOffers()
  wallArea = perimeter×height − Σ openings        │
  litres = area×coats ÷ coverage ×(1+wastage)     │
        │                                          ▼
        ▼                                  [4] PackOptimizer
[2] RuleEngine ────────────────────────►   cheapest combo of 1/4/10/20 L
  recommends surface-appropriate            (+ wastage-aware, min-pack rules)
  product line, primer, coats                     │
  + prep items with rationale                     ▼
        │                                  [5] CostBreakdownBuilder
        └──────────────────────────────►   paint/primer/prep/accessories/
                                           labour/GST/contingency,
                                           per-room + house, min/likely/max
                                                  │
                                                  ▼
                                           [6] Insights: save-money tips,
                                           Good/Better/Best, budget solver,
                                           shopping list grouped by retailer
```

Formulas (exactly what the UI will render under "How is this calculated?"):

```
grossWallArea   = Σ (edgeLength × wallHeight)                      [sq ft]
openingDeduction= Σ over openings (width × height × deductFactor)
netPaintArea    = grossWallArea − openingDeduction + ceilingArea(if painted)
litresRequired  = netPaintArea × coats ÷ coveragePerLitre × (1 + wastagePct/100)
paintCost       = min-cost pack combination covering litresRequired
primerCost      = netPaintArea × primerCoats ÷ primerCoverage × price/L  (if required)
prepCost        = Σ enabled PrepItem(rate₹/sqft × applicableArea)
accessoryCost   = Σ qty × unitPrice  (qty auto-derived from area & rollers/brushes-per-100sqft)
labourCost      = perSqft ? rate × netPaintArea
                            : days × painters × dayRate,
                days = ceil(netPaintArea / (painters × productivitySqftPerDay))
subtotal        = paint + primer + prep + accessories + labour
gst             = subtotal × gstPct/100
contingency     = (paint+primer+prep+accessories) × contingencyPct/100   (materials only)
total           = subtotal + gst + contingency
range(min/max)  = total × (1 ± uncertainty), uncertainty from condition unknowns
```

Uncertainty model (drives min/likely/max): damp/cracked conditions add ±12% (hidden substrate work), unknown prior coats ±8%, exterior height/scaffolding ±10%, DIY mode ±4%. Shown to the user as plain text.

---

## 6. Live price engine

### 6.1 Source priority (server-side, documented per-source in `backend/SOURCES.md`)
1. Official retailer/brand APIs & affiliate feeds (Amazon PA-API, Flipkart Affiliate) — permissioned only.
2. Licensed product-data / price-comparison APIs.
3. Crowdsourced submissions (`UserPriceSubmission`) — OCR via ML Kit on-device, then outlier detection (`price ∉ [median×0.6, median×1.6]` rejected) and ≥2 independent upvotes before promotion.
4. Admin-curated fallback (`seed/brands.json`) — always present, ships with the APK.

### 6.2 Normalisation & matching
Worker ingests raw feeds → canonical shape:

```json
{ "productId":"ap-dreamwash-one-coat","retailerId":"amazon_in",
  "priceRupees":2499,"packSizeLitre":10,"currency":"INR",
  "pincodePrefix":"560","inStock":true,
  "productUrl":"https://...?tag=wallora-21","observedAt":"ISO-8601","confidence":0.95 }
```

Name→ProductID matching = fuzzy token match (Jaro-Winkler ≥0.88 on brand+line+pack) **plus** a manual `product_alias` mapping table maintained in the admin dashboard. Unmatched rows land in a review queue instead of polluting prices.

### 6.3 Client behaviour
* `Freshness`: LIVE (<6 h) · TODAY (<24 h) · STALE (<7 d) · MANUAL (seed/override). Badges drawn from `observedAt`.
* Override wins: `PriceOverride(productId)` short-circuits resolution; "Reset to live" deletes it.
* Failure path: `SyncState.FAILED` → keep cache, show non-blocking banner, retry with exponential backoff via WorkManager (`NetworkType.CONNECTED`, optional `METERED_NOT` for Wi-Fi-only).
* Pull-to-refresh triggers a one-off expedited sync. Preferred-retailer filter applied client-side.
* Price history endpoint returns 30/90/180-day series; client draws chart and computes "Good time to buy / Wait" (current vs 90-day median ±5%). Alerts = scheduled notification when observed drop ≥ threshold.

---

## 7. Backend (Phase 4 deliverable, scaffolded now)

```
backend/
├── wrangler.toml               Worker + Cron Trigger (every 12 h) + D1 binding
├── schema.sql                  brands, product_lines, products, pack_sizes,
│                               retailers, offers, price_history, aliases,
│                               submissions, overrides, users
├── src/index.ts                REST: GET /v1/catalog, /v1/offers?productId&pincode,
│                               /v1/history?productId&days, POST /v1/submissions
├── src/sync.ts                 fetch sources → normalize → dedupe → write snapshots
├── src/normalize.ts            name canonicalisation + alias/fuzzy matcher
└── public/admin.html           review/override/approve queue (static + /v1/admin JWT)
```

Auth: Google ID-token (optional sign-in); anonymous users get read-only catalogue + can submit prices with explicit consent.

---

## 8. UI architecture (Phases 2-6)

Single-Activity Compose, type-safe navigation (`NavHost` routes: `plan/{projectId}`, `rooms`, `colors`, `paint-config`, `comparison`, `summary`, `quotes`, `settings`). Editor is a `Canvas` + `pointerInput` layer over a retained `EditorState` (immutable snapshot + command stack for undo/redo persisted per session). ViewModels expose `StateFlow<UiState>`; engines injected as pure interfaces.

Theming: Material You dynamic colour with Wallora fallback palette — deep indigo/teal base `#0F4C5C`, vibrant coral accent `#FF6B57`, neutral grey scale; Poppins/Manrope headings + Inter body; adaptive icon + animated roller splash (Lottie-free, Canvas animation).

---

## 9. Phase plan & status

See `docs/PHASES.md`. Phase 1 = data model + calculation engine + pack optimizer + tests (**delivered in this commit**), plus repo skeleton, seed catalogue, README, and backend scaffold so later phases never restructure the core.
