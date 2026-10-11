package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Retry-schedule parity with qr v0.7.2: after the round-0 prepare + mandatory pick of every layer
 * (coarsest first), `buildSchedule` (decode.ts L2895-3005) must leave the same sorted
 * `[rank, inverted, i0, i1, i2]` records (exact doubles) and `nextSet` (L3006-3024) must pop the
 * same triples until exhaustion. Fixtures: src/test/resources/dumps/schedule
 * (`tools/js-dump/dump-layers.mjs --schedule`).
 */
class ScheduleDumpTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("qr.LayerDumpTest#fixtures")
    fun scheduleMatchesJs(name: String) {
        val text = ScheduleDumpTest::class.java.getResource("/dumps/schedule/$name.json")!!.readText()
        val dump = MiniJson.parse(text) as Map<*, *>
        val scanner = QRScanner((dump["maxWidth"] as Double).toInt(), (dump["maxHeight"] as Double).toInt())
        scanner.addImage(LayerDumpTest.image(LayerDumpTest.load(name)))
        scanner.ensureResized()
        val expected = (dump["layers"] as List<*>).map { it as Map<*, *> }.sortedByDescending { it["index"] as Double }
        for (e in expected) {
            val layer = scanner.layers[(e["index"] as Double).toInt()]
            scanner.prepareLayer(layer)
            scanner.pickMandatory(layer)
        }
        for (e in expected) {
            val i = (e["index"] as Double).toInt()
            val layer = scanner.layers[i]
            val at = "$name layer $i"
            scanner.buildSchedule(layer)
            assertEquals((e["setCount"] as Double).toInt(), layer.setCount, "$at setCount")
            for ((k, set) in (e["sets"] as List<*>).withIndex()) {
                set as List<*>
                for (f in 0 until 5) assertEquals(set[f] as Double, layer.sets[k * 5 + f], "$at set $k field $f")
            }
            val pops = e["pops"] as List<*>
            for ((k, pop) in pops.withIndex()) {
                pop as List<*>
                val t = scanner.nextSet(layer) ?: error("$at pop $k: schedule exhausted early")
                assertEquals(
                    listOf((pop[0] as Double).toInt(), (pop[1] as Double).toInt(), (pop[2] as Double).toInt(), pop[3] as Boolean),
                    listOf(t.tlIndex, t.trIndex, t.blIndex, t.inverted),
                    "$at pop $k",
                )
            }
            assertEquals(null, scanner.nextSet(layer), "$at exhausted after ${pops.size} pops")
        }
    }
}
