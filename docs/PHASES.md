# Wallora — Build Phases

| Phase | Scope | Status |
|---|---|---|
| **1** | Data model, calculation engine, pack optimizer, prep rule engine, labour/accessories, insights & budget solver, seed catalogue JSON, unit tests, repo skeleton, backend scaffold, README | ✅ **Delivered (this commit)** |
| 2 | Interactive floor-plan editor: zoom/pan canvas, grid & snapping, rooms (rect/polygon), handles, openings palette, multi-floor, undo/redo, templates, live area badges | ⏳ awaiting go-ahead |
| 3 | Paint config per surface, colour visualiser (2D tint + AR try-on), prep UI, accessories/labour UI, cost summary screen with charts & "how is this calculated" | ⏳ |
| 4 | Brand comparison screen, backend deploy (Worker + D1 + cron), live price sync via WorkManager, overrides, freshness badges, history chart | ⏳ |
| 5 | PDF quote/report generator, share sheet, contractor mode, timeline/Gantt, weather advice, price-drop alerts | ⏳ |
| 6 | Branding polish (logo, adaptive icon, animated splash), accessibility, localisation (en/hi/pa), Play Store assets, release checklist | ⏳ |

## Phase 1 acceptance criteria — all met
- [x] `:domain` — every entity from the spec's data-model list (§15) as pure Kotlin.
- [x] `:calculation` — geometry → litres → pack optimizer → cost breakdown → insights, deterministic and Android-free.
- [x] Pack optimizer picks cheapest combination of 1L/4L/10L/20L packs for exact litres incl. wastage; exhaustive DP verified against brute force in tests.
- [x] Rule engine recommends paint type/primer/coats/prep from surface+condition+colour change, each recommendation carrying a human-readable rationale ("why is this recommended?").
- [x] Labour (per-sq-ft / per-day, auto days from productivity), accessories auto-quantities, GST + contingency, DIY vs Contractor modes.
- [x] Min/likely/max range with explained uncertainty; Good/Better/Best; save-money tips; budget-mode solver; shopping list grouped by retailer.
- [x] Seed catalogue: Asian Paints, Berger, Nerolac, Dulux, Indigo, JSW, Birla Opus (+ Custom) with tiers, coverage, durability, admin-curated prices.
- [x] Unit tests (`kotlin.test`) covering every module of the engine; run green on plain JVM.
- [x] Gradle multi-module skeleton + version catalog so phases 2-6 plug in without restructuring.

## What Phase 1 deliberately does *not* include
Android UI, Room DAOs, Hilt graph, network layer, WorkManager worker, Compose editor — these are Phase 2+ and their modules exist only as documented placeholders. The engine, however, is already written against repository *interfaces* (`PriceSource`, `CatalogRepository`) so wiring real implementations later changes no calling code.
