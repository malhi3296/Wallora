package in.wallora.calculation

import kotlin.math.abs

/**
 * Brute-force reference implementation of the pack optimizer, used ONLY by tests
 * to prove the DP result is truly minimal within the overshoot window.
 */
internal object PackBruteForce {

    fun cheapest(litresNeeded: Double, packs: Map<Double, Double>, maxOvershoot: Int = 4): Pair<List<Int>, Double>? {
        val need = kotlin.math.ceil(litresNeeded - 1e-9).toInt().coerceAtLeast(1)
        val sizes = packs.keys.toList()
        val limit = need + maxOvershoot
        var best: List<Int>? = null
        var bestCost = Double.MAX_VALUE

        // counts per pack size, bounded so total litres <= limit
        fun rec(idx: Int, litresSoFar: Int, cost: Double, counts: MutableList<Int>) {
            if (idx == sizes.size) {
                if (litresSoFar >= need && cost < bestCost) { bestCost = cost; best = counts.toList() }
                return
            }
            val size = sizes[idx].toInt()
            if (size <= 0) return
            var count = 0
            while (litresSoFar + count * size <= limit) {
                counts[idx] = count
                rec(idx + 1, litresSoFar + count * size, cost + count * packs.getValue(sizes[idx]), counts)
                count++
            }
            counts[idx] = 0
        }
        rec(0, 0, 0.0, MutableList(sizes.size) { 0 })
        best ?: return null
        val purchases = best!!.filterIndexed { i, c -> c > 0 }.map { sizes[it] to it }
        return best!!.toList() to bestCost
    }

    fun totalLitres(counts: List<Int>, sizes: List<Double>) =
        counts.mapIndexed { i, c -> c * sizes[i] }.sum()

    fun approx(a: Double, b: Double, eps: Double = 0.01) = abs(a - b) < eps
}
