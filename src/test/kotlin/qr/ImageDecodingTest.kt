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
}
