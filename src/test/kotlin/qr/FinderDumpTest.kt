package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Finder search + mandatory pick parity with qr v0.7.2: per layer (coarsest first, as the JS
 * round-0 walk) every finder record (x, y, ms, count, state) in insertion order and the pick
 * (both polarity results, chosen polarity, triple, pickSum/Lo/Hi) compared with exact doubles
 * against src/test/resources/dumps/layers (tools/js-dump/dump-layers.mjs).
 */
class FinderDumpTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("qr.LayerDumpTest#fixtures")
    fun findersAndPickMatchJs(name: String) {
        val dump = LayerDumpTest.load(name)
        val scanner = QRScanner(maxWidth = (dump["maxWidth"] as Double).toInt(), maxHeight = (dump["maxHeight"] as Double).toInt())
        scanner.addImage(LayerDumpTest.image(dump))
        scanner.ensureResized()
        val expected = (dump["layers"] as List<*>).map { it as Map<*, *> }
        assertEquals(expected.size, scanner.layers.count { it.used }, "used layer count")
        for (e in expected.sortedByDescending { it["index"] as Double }) {
            val i = (e["index"] as Double).toInt()
            val layer = scanner.layers[i]
            scanner.prepareLayer(layer)
            val at = "$name layer $i"
            val patterns = e["patterns"] as List<*>
            assertEquals(patterns.size, layer.patternCount, "$at patternCount")
            for ((k, p) in patterns.withIndex()) {
                p as List<*>
                val pos = k * 4
                for (f in 0 until 4) assertEquals(p[f] as Double, layer.patterns[pos + f], "$at record $k field $f")
                assertEquals((p[4] as Double).toInt(), layer.state[k].toInt(), "$at record $k state")
            }
            val pick = e["pick"] as Map<*, *>
            val triple = scanner.pickMandatory(layer)
            // pickPolarity(1) leaves tmp64[0..3] alone, so both polarity results are still readable.
            fun polarity(key: String, base: Int) {
                val want = pick[key] as List<*>? ?: return
                for (f in 0 until 4) assertEquals(num(want[f]), scanner.tmp64[base + f], "$at $key[$f]")
            }
            polarity("ordinary", 0)
            polarity("inverted", 4)
            assertEquals(pick["pickSum"] as Double, layer.pickSum, "$at pickSum")
            assertEquals(pick["pickLo"] as Double, layer.pickLo, "$at pickLo")
            assertEquals(pick["pickHi"] as Double, layer.pickHi, "$at pickHi")
            val t = pick["triple"] as Map<*, *>?
            if (t == null) {
                assertNull(triple, "$at triple")
                assertNull(pick["ordinary"], "$at ordinary")
                assertNull(pick["inverted"], "$at inverted")
                continue
            }
            assertNotNull(triple, "$at triple")
            triple!!
            assertEquals((pick["polarity"] as Double) == 1.0, triple.inverted, "$at polarity")
            assertEquals(t["inverted"] as Boolean, triple.inverted, "$at inverted")
            for ((role, pattern) in listOf("tl" to triple.tl, "tr" to triple.tr, "bl" to triple.bl)) {
                val want = t[role] as List<*>
                assertEquals(want[0] as Double, pattern.x, "$at $role.x")
                assertEquals(want[1] as Double, pattern.y, "$at $role.y")
                assertEquals(want[2] as Double, pattern.ms, "$at $role.ms")
            }
            assertEquals((t["tlIndex"] as Double).toInt(), triple.tlIndex, "$at tlIndex")
            assertEquals((t["trIndex"] as Double).toInt(), triple.trIndex, "$at trIndex")
            assertEquals((t["blIndex"] as Double).toInt(), triple.blIndex, "$at blIndex")
        }
    }

    private fun num(v: Any?): Double = if (v is String) v.toDouble() else v as Double
}
