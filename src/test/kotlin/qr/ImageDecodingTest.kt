package qr

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Strict JS parity over every test image (565: BoofCV detection + decoding, issues, misc).
 *
 * - Default mode: `QRScanner(w, h).decode()` must return the same text as JS, or fail with the
 *   same tag (`dumps/decode-default.json`, tools/js-dump/dump-decode.mjs).
 * - All mode: `QRScanner(w, h, effort = Int.MAX_VALUE, timeLimitMs = +Inf).decode(all = true)` must
 *   return the same ordered result list and terminal failure tag (`dumps/decode-all.json`,
 *   `dump-decode.mjs --all`).
 * - JS table (decode.test.ts L1842-1866) for detection categories: the default first result is one
 *   of the DECODED payloads (absent for unlisted and DECODED_ALL_ONLY images), and the sorted
 *   distinct all-mode results equal the sorted DECODED list.
 *
 * - Points mode (`dumps/decode-points.json.gz`, `dump-decode.mjs --points`): the default scan with
 *   `DecodeOptions(pointsOnDetect = ...)` must give the same result/tag AND the same reported
 *   [FinderPoints] (every number equal under `java.lang.Double.compare`). Setting the callback makes
 *   `report()` rewrite the scanner homography before the v7+ tile fallbacks, exactly as in JS, so
 *   this pass also proves that hazard is reproduced. Images whose points-mode outcome differs from
 *   `decode-default.json` are printed (information only). The all-mode variant uses
 *   `dumps/decode-points-all.json.gz` (ordered event list per image).
 *
 * Every mismatch path is collected and asserted at once per group; per-group and total summaries
 * are printed. [KNOWN_DIVERGENCES] is empty: no JS-only artefact has been found.
 */
@Tag("exhaustive")
@Execution(ExecutionMode.CONCURRENT) // groups of both passes run in parallel
class ImageDecodingTest {
    companion object {
        /** Paths whose JS result cannot be reproduced on the JVM (none; each entry needs a reason). */
        private val KNOWN_DIVERGENCES: Set<String> = emptySet()

        private val fixture: Map<String, Pair<String?, String?>> by lazy {
            val text = ImageDecodingTest::class.java.getResource("/dumps/decode-default.json")!!.readText()
            (MiniJson.parse(text) as Map<*, *>).entries.associate { (k, v) ->
                val entry = v as Map<*, *>
                k as String to Pair(entry["result"] as String?, entry["fail"] as String?)
            }
        }

        private val allFixture: Map<String, Pair<List<String>, String?>> by lazy {
            val text = ImageDecodingTest::class.java.getResource("/dumps/decode-all.json")!!.readText()
            (MiniJson.parse(text) as Map<*, *>).entries.associate { (k, v) ->
                val entry = v as Map<*, *>
                k as String to Pair((entry["results"] as List<*>).map { it as String }, entry["fail"] as String?)
            }
        }

        /** One points-mode fixture entry: outcome plus the flattened pointsOnDetect events. */
        private class PointsEntry(val results: List<String>, val fail: String?, val events: List<Any?>)

        private fun resourceText(name: String): String {
            val stream = ImageDecodingTest::class.java.getResourceAsStream(name)!!
            return (if (name.endsWith(".gz")) GZIPInputStream(stream) else stream).use { it.readBytes().decodeToString() }
        }

        /** Fixture numbers are doubles or the strings "NaN", "Infinity", "-Infinity", "-0". */
        internal fun fixtureNumbers(v: Any?): Any? = when (v) {
            is List<*> -> v.map(::fixtureNumbers)
            is Double -> v
            "NaN" -> Double.NaN
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            "-0" -> -0.0
            else -> error("unexpected fixture number $v")
        }

        private fun pointsFixture(name: String, all: Boolean): Map<String, PointsEntry> =
            (MiniJson.parse(resourceText(name)) as Map<*, *>).entries.associate { (k, v) ->
                val e = v as Map<*, *>
                val results = if (all) (e["results"] as List<*>).map { it as String } else listOfNotNull(e["result"] as String?)
                k as String to PointsEntry(results, e["fail"] as String?, fixtureNumbers(e["points"]) as List<Any?>)
            }

        private val pointsFixture: Map<String, PointsEntry> by lazy { pointsFixture("/dumps/decode-points.json.gz", false) }
        private val pointsAllFixture: Map<String, PointsEntry> by lazy { pointsFixture("/dumps/decode-points-all.json.gz", true) }

        /** Same layout as dump-decode.mjs `flatEvent`. */
        private fun flat(p: FinderPoints): List<Any> {
            fun pt(q: Point) = listOf(q.x, q.y)
            fun quad(q: Quad) = q.flatMap(::pt)
            fun finder(f: FinderPoint) = listOf(f.x, f.y, f.moduleSize) + quad(f.corners)
            val b = p.boundingBox
            return listOf(finder(p.tl), finder(p.tr), pt(p.br), finder(p.bl), p.aligners.map(::finder),
                quad(p.bounds), quad(p.outline), listOf(b.x, b.y, b.width, b.height))
        }

        /** First difference between two nested number lists (`Double.compare` per number), or null. */
        private fun firstDiff(js: Any?, kt: Any?, at: String = ""): String? {
            if (js is List<*> && kt is List<*>) {
                for (i in 0 until minOf(js.size, kt.size)) firstDiff(js[i], kt[i], "$at[$i]")?.let { return it }
                return if (js.size != kt.size) "$at size js=${js.size} kt=${kt.size}" else null
            }
            if (js is Double && kt is Double) return if (java.lang.Double.compare(js, kt) == 0) null else "$at js=$js kt=$kt"
            return "$at js=$js kt=$kt"
        }

        private val pointsTotal = AtomicInteger()
        private val pointsMatched = AtomicInteger()
        private val pointsAllTotal = AtomicInteger()
        private val pointsAllMatched = AtomicInteger()
        private val hazards: MutableSet<String> = java.util.concurrent.ConcurrentSkipListSet()

        private val allTotal = AtomicInteger()
        private val allMatched = AtomicInteger()
        private val total = AtomicInteger()
        private val matched = AtomicInteger()
        private val firstOk = AtomicInteger()
        private val firstDecoded = AtomicInteger()
        private val tableTotal = AtomicInteger()
        private val allSetOk = AtomicInteger()
        private val allSetDecoded = AtomicInteger()
        private val allTableTotal = AtomicInteger()

        /** Detection categories plus the decoding/issues/misc vector groups. */
        @JvmStatic
        fun groups(): List<String> = TestImages.listCategories() + listOf("decoding", "issues", "misc")

        private fun images(group: String): List<String> = when (group) {
            "decoding" -> list("boofcv-v3/decoding", Regex("\\.png$", RegexOption.IGNORE_CASE))
            "issues" -> list("issues", Regex("\\.jpe?g$", RegexOption.IGNORE_CASE))
            "misc" -> list("misc", Regex("\\.png$", RegexOption.IGNORE_CASE))
            else -> TestImages.listImages(group).map { "boofcv-v3/detection/$group/$it" }
        }

        private fun list(dir: String, re: Regex): List<String> =
            (File(TestImages.vectorsRoot, dir).listFiles() ?: emptyArray())
                .filter { it.isFile && re.containsMatchIn(it.name) }.map { "$dir/${it.name}" }.sorted()

        @JvmStatic
        @AfterAll
        fun summary() {
            if (pointsTotal.get() > 0)
                println("ImageDecodingTest total: JS points match ${pointsMatched.get()}/${pointsTotal.get()}, " +
                    "hazard images (points-mode outcome != decode-default): ${hazards.size}" +
                    hazards.joinToString("") { "\n  $it" })
            if (pointsAllTotal.get() > 0)
                println("ImageDecodingTest total: JS all-mode points match ${pointsAllMatched.get()}/${pointsAllTotal.get()}")
            if (allTotal.get() > 0)
                println("ImageDecodingTest total: JS all-mode match ${allMatched.get()}/${allTotal.get()}, " +
                    "table all-set ok ${allSetOk.get()}/${allTableTotal.get()} (decoding ${allSetDecoded.get()})")
            if (total.get() > 0)
                println("ImageDecodingTest total: JS default-decode match ${matched.get()}/${total.get()}, " +
                    "table default-first ok ${firstOk.get()}/${tableTotal.get()} (decoding ${firstDecoded.get()})")
        }

        private fun show(s: String): String = '"' + (if (s.length > 60) s.take(60) + "..." else s)
            .replace("\r", "\\r").replace("\n", "\\n") + '"'

        private fun report(group: String, label: String, ok: Int, n: Int, extra: String, mismatches: List<String>) {
            println((listOf("$group: $label $ok/$n$extra") + mismatches.map { "  $it" }).joinToString("\n"))
            assertEquals(emptyList<String>(), mismatches.filter { m -> KNOWN_DIVERGENCES.none { m.startsWith("$it ") } })
        }
    }

    /** Every fixture key is an image the Kotlin listing visits, and vice versa. */
    @Test
    fun fixtureKeysMatchListing() {
        val listed = groups().flatMap(::images).toSortedSet()
        assertEquals(565, listed.size)
        assertEquals(listed, fixture.keys.toSortedSet())
        assertEquals(listed, allFixture.keys.toSortedSet())
        assertEquals(listed, pointsFixture.keys.toSortedSet())
        assertEquals(listed, pointsAllFixture.keys.toSortedSet())
        val table = ImageVectors.DECODED.flatMap { (cat, files) -> files.keys.map { "boofcv-v3/detection/$cat/$it" } }
        assertEquals(emptyList<String>(), table.filter { it !in listed })
        assertEquals(emptyList<String>(), ImageVectors.DECODED_ALL_ONLY.map { "boofcv-v3/$it" }.filter { it !in listed })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    fun group(group: String) {
        val paths = images(group)
        var match = 0
        var ok = 0
        var decodedFirst = 0
        val mismatches = ArrayList<String>()
        for (path in paths) {
            val img = TestImages.readLuma(path)
            val scanner = QRScanner(img.width, img.height)
            scanner.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
            val first = scanner.decode().firstOrNull()
            val tag = if (first == null) scanner.lastFailure?.tag else null
            val js = fixture[path]
            if (js != null && js.first == first && js.second == tag) match++
            else mismatches.add("$path js=${js?.let { it.first?.let(::show) ?: it.second }} kt=${first?.let(::show) ?: tag}")
            if (group in ImageVectors.DECODED) {
                val file = path.substringAfterLast('/')
                val decoded = ImageVectors.DECODED.getValue(group)[file]
                val allOnly = "detection/$group/$file" in ImageVectors.DECODED_ALL_ONLY
                val good = if (decoded == null || allOnly) first == null else first != null && first in decoded
                if (good) ok++
                else mismatches.add("$path first-rule expected=${if (decoded == null || allOnly) "[]" else decoded} got=${first?.let(::show)} fail=$tag")
                if (first != null) decodedFirst++
            }
        }
        total.addAndGet(paths.size)
        matched.addAndGet(match)
        val rule = if (group in ImageVectors.DECODED) {
            firstOk.addAndGet(ok)
            firstDecoded.addAndGet(decodedFirst)
            tableTotal.addAndGet(paths.size)
            ", table default-first ok $ok/${paths.size} (decoding $decodedFirst)"
        } else ""
        report(group, "JS default-decode match", match, paths.size, rule, mismatches)
    }

    @ParameterizedTest(name = "all {0}")
    @MethodSource("groups")
    fun allMode(group: String) {
        val paths = images(group)
        var match = 0
        var ok = 0
        var decoding = 0
        val mismatches = ArrayList<String>()
        for (path in paths) {
            val img = TestImages.readLuma(path)
            val scanner = QRScanner(img.width, img.height, effort = Int.MAX_VALUE, timeLimitMs = Double.POSITIVE_INFINITY)
            scanner.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
            val results = scanner.decode(all = true)
            val tag = scanner.lastFailure?.tag
            val js = allFixture[path]
            if (js != null && js.first == results && js.second == tag) match++
            else mismatches.add("$path all js=${js?.let { it.first.map(::show) + it.second }} kt=${results.map(::show) + tag}")
            if (group in ImageVectors.DECODED) {
                val expected = ImageVectors.DECODED.getValue(group)[path.substringAfterLast('/')].orEmpty().sorted()
                val all = results.distinct().sorted()
                if (all == expected) ok++
                else mismatches.add("$path all-set expected=$expected got=${all.map(::show)}")
                if (all.isNotEmpty()) decoding++
            }
        }
        allTotal.addAndGet(paths.size)
        allMatched.addAndGet(match)
        val rule = if (group in ImageVectors.DECODED) {
            allSetOk.addAndGet(ok)
            allSetDecoded.addAndGet(decoding)
            allTableTotal.addAndGet(paths.size)
            ", table all-set ok $ok/${paths.size} (decoding $decoding)"
        } else ""
        report(group, "JS all-mode match", match, paths.size, rule, mismatches)
    }

    @ParameterizedTest(name = "points {0}")
    @MethodSource("groups")
    fun points(group: String) {
        val paths = images(group)
        var match = 0
        val mismatches = ArrayList<String>()
        for (path in paths) {
            val img = TestImages.readLuma(path)
            val events = ArrayList<Any?>()
            val scanner = QRScanner(img.width, img.height, DecodeOptions(pointsOnDetect = { p, _ -> events.add(flat(p)) }))
            scanner.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
            val first = scanner.decode().firstOrNull()
            val tag = if (first == null) scanner.lastFailure?.tag else null
            val plain = fixture[path]
            if (plain != null && (plain.first != first || plain.second != tag))
                hazards.add("$path default js=${plain.first?.let(::show) ?: plain.second} points-mode kt=${first?.let(::show) ?: tag}")
            val js = pointsFixture[path]
            val diff = when {
                js == null -> "missing fixture"
                js.results != listOfNotNull(first) || js.fail != tag ->
                    "result js=${js.results.firstOrNull()?.let(::show) ?: js.fail} kt=${first?.let(::show) ?: tag}"
                else -> firstDiff(js.events, events)?.let { "points$it" }
            }
            if (diff == null) match++ else mismatches.add("$path $diff")
        }
        pointsTotal.addAndGet(paths.size)
        pointsMatched.addAndGet(match)
        report(group, "JS points match", match, paths.size, "", mismatches)
    }

    @ParameterizedTest(name = "points all {0}")
    @MethodSource("groups")
    fun pointsAllMode(group: String) {
        val paths = images(group)
        var match = 0
        val mismatches = ArrayList<String>()
        for (path in paths) {
            val img = TestImages.readLuma(path)
            val events = ArrayList<Any?>()
            val scanner = QRScanner(img.width, img.height, DecodeOptions(
                effort = Int.MAX_VALUE, timeLimitMs = Double.POSITIVE_INFINITY,
                pointsOnDetect = { p, _ -> events.add(flat(p)) },
            ))
            scanner.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
            val results = scanner.decode(all = true)
            val tag = scanner.lastFailure?.tag
            val js = pointsAllFixture[path]
            val diff = when {
                js == null -> "missing fixture"
                js.results != results || js.fail != tag ->
                    "results js=${js.results.map(::show) + js.fail} kt=${results.map(::show) + tag}"
                else -> firstDiff(js.events, events)?.let { "points$it" }
            }
            if (diff == null) match++ else mismatches.add("$path all $diff")
        }
        pointsAllTotal.addAndGet(paths.size)
        pointsAllMatched.addAndGet(match)
        report(group, "JS all-mode points match", match, paths.size, "", mismatches)
    }
}
