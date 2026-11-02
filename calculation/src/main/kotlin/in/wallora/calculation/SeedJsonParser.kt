package in.wallora.calculation

import in.wallora.domain.model.*

/**
 * Minimal recursive-descent JSON parser (objects/arrays/strings/numbers/bools).
 * ~150 lines, no dependencies — used only for the trusted bundled seed file.
 * Production network JSON goes through Moshi in :network (Phase 4).
 */
internal object SeedJsonParser {

    fun parse(text: String): SeedCatalog {
        val root = Parser(text).parseValue() as Map<*, *>
        val brands = (root["brands"] as List<*>).map {
            val o = it as Map<String, Any?>
            Brand(id = o["id"] as String, name = o["name"] as String,
                  country = o["country"] as String? ?: "IN", affiliateTag = o["affiliateTag"] as String?)
        }
        val retailers = (root["retailers"] as List<*>).map {
            val o = it as Map<String, Any?>
            Retailer(
                id = o["id"] as String, name = o["name"] as String,
                homepageUrl = o["homepageUrl"] as String? ?: "",
                sourceKind = PriceSourceKind.valueOf(o["sourceKind"] as String),
                supportsPincode = o["supportsPincode"] as Boolean? ?: true,
            )
        }
        val surfacesByName: Map<String, SurfaceType> = SurfaceType.entries.associateBy { it.name }
        val paintTypes = PaintType.entries.associateBy { it.name }
        val products = (root["products"] as List<*>).map {
            val o = it as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val priceMap = (o["seedPricePerPack"] as Map<String, Any?>).entries
                .associate { (k, v) -> k.toDouble() to (v as Number).toDouble() }
            Product(
                id = o["id"] as String,
                lineId = o["lineId"] as String? ?: "",
                brandId = o["brandId"] as String,
                name = o["name"] as String,
                paintType = paintTypes.getValue(o["paintType"] as String),
                tier = Tier.valueOf(o["tier"] as String),
                surfaces = (o["surfaces"] as List<*>).map { s -> surfacesByName.getValue(s as String) }.toSet(),
                coveragePerLitre = (o["coveragePerLitre"] as Number).toDouble(),
                durabilityYears = (o["durabilityYears"] as Number).toInt(),
                washable = o["washable"] as Boolean,
                vocLevel = VocLevel.entries.firstOrNull { v -> v.name == o["vocLevel"] } ?: VocLevel.LOW,
                minTempCureC = (o["minTempCureC"] as Number?)?.toDouble() ?: 10.0,
                extraCoatsForDarkBase = (o["extraCoatsForDarkBase"] as Number?)?.toInt() ?: 1,
                packs = (o["packs"] as List<*>).map { p -> PackSize((p as Number).toDouble()) },
                seedPricePerPack = priceMap,
                isPrimer = o["isPrimer"] as Boolean? ?: false,
            )
        }
        val colors = (root["colors"] as List<*>).map {
            val o = it as Map<String, Any?>
            ColorShade(
                id = o["id"] as String, brandId = o["brandId"] as String, name = o["name"] as String,
                hex = (o["hex"] as Number).toInt(), code = o["code"] as String? ?: "",
                luminanceWeight = (o["luminanceWeight"] as Number?)?.toDouble() ?: 0.5,
                trending = o["trending"] as Boolean? ?: false,
            )
        }
        return SeedCatalog(brands, retailers, products, colors)
    }

    // ------------------------------------------------------------------ core

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) parseNumber() else error("Unexpected '$c' at $i")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            expect('{'); skipWs()
            if (s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs(); expect(':')
                map[key] = parseValue()
                skipWs()
                when (s[i]) { ',' -> i++; '}' -> { i++; return map }; else -> error("Expected , or } at $i") }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            expect('['); skipWs()
            if (s[i] == ']') { i++; return list }
            while (true) {
                list += parseValue()
                skipWs()
                when (s[i]) { ',' -> i++; ']' -> { i++; return list }; else -> error("Expected , or ] at $i") }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        '/' -> sb.append('/'); '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> error("bad escape \\$e")
                    }
                    i++
                } else sb.append(s[i++])
            }
            i++ // closing quote
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDouble()
        }

        private fun parseBool(): Boolean = if (s[i] == 't') { expect("true"); true } else { expect("false"); false }

        private fun expect(t: String) { for (c in t) { if (s[i] != c) error("Expected '$t' at $i"); i++ } }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}
