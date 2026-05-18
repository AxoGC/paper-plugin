package net.axogc.paper.stats

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Statistic

/**
 * Derives the six unified radar axes from Bukkit Statistic API:
 *  - walk_total_m   = (WALK_ONE_CM + SPRINT_ONE_CM) / 100, integer meters
 *  - play_time      = PLAY_ONE_MINUTE ticks / 20, integer seconds
 *  - survival_index = play_time_s / (deaths + 30)
 *  - blocks_placed  = Σ USE_ITEM over block-form items
 *  - blocks_broken  = Σ MINE_BLOCK over all materials
 *  - kills_total    = MOB_KILLS + PLAYER_KILLS
 *
 * The order of [AXIS_KEYS] is the wire order and the radar axis order — do not
 * reshuffle without coordinating with the web side.
 *
 * `percent` is the value normalized against a fixed scale (see [SCALES]). The
 * scales are deliberate constants, not server-tuned: a new server should still
 * be able to render a meaningful radar on day one. Tune here when balance
 * shifts; the protocol carries percent so core/web never need to know the
 * scale at all.
 */
object StatsCollector {

    val AXIS_KEYS: List<String> = listOf(
        "walk_total_m",
        "play_time",
        "survival_index",
        "blocks_placed",
        "blocks_broken",
        "kills_total",
    )

    private val UNITS: Map<String, String> = mapOf(
        "walk_total_m" to "m",
        "play_time" to "s",
        "survival_index" to "",
        "blocks_placed" to "",
        "blocks_broken" to "",
        "kills_total" to "",
    )

    private val SCALES: Map<String, Double> = mapOf(
        "walk_total_m" to 50_000.0,    // 50 km
        "play_time" to 360_000.0,      // 100 h
        "survival_index" to 8_000.0,
        "blocks_placed" to 80_000.0,
        "blocks_broken" to 80_000.0,
        "kills_total" to 5_000.0,
    )

    // Pre-filter material sets so we don't pay isBlock / isItem per call. Bukkit
    // expects matching Material type for typed Statistic.getStatistic — wrong
    // category throws IllegalArgumentException.
    private val BLOCK_ITEMS: List<Material> = Material.values().filter { it.isItem && it.isBlock }
    private val MINEABLE: List<Material> = Material.values().filter { it.isBlock }

    data class Axis(val key: String, val unit: String, val value: Double, val percent: Double)

    /** Compute all six axes for one player. */
    fun computeAxes(p: OfflinePlayer): List<Axis> {
        val walkCm = readUntyped(p, Statistic.WALK_ONE_CM) + readUntyped(p, Statistic.SPRINT_ONE_CM)
        val walkM = walkCm / 100.0
        val playTimeS = readUntyped(p, Statistic.PLAY_ONE_MINUTE) / 20.0
        val deaths = readUntyped(p, Statistic.DEATHS)
        val survival = playTimeS / (deaths + 30.0)
        val placed = sumTyped(p, Statistic.USE_ITEM, BLOCK_ITEMS)
        val broken = sumTyped(p, Statistic.MINE_BLOCK, MINEABLE)
        val kills = readUntyped(p, Statistic.MOB_KILLS) + readUntyped(p, Statistic.PLAYER_KILLS)

        val raw = mapOf(
            "walk_total_m" to walkM,
            "play_time" to playTimeS,
            "survival_index" to survival,
            "blocks_placed" to placed.toDouble(),
            "blocks_broken" to broken.toDouble(),
            "kills_total" to kills.toDouble(),
        )
        return AXIS_KEYS.map { key ->
            val v = raw.getValue(key)
            val scale = SCALES.getValue(key)
            val percent = if (scale > 0) v / scale * 100.0 else 0.0
            Axis(key, UNITS.getValue(key), v, percent)
        }
    }

    /** Serializes [computeAxes] to a JsonArray (wire format for `stats.update` / stats.fetch reply). */
    fun computeAxesJson(p: OfflinePlayer): JsonArray {
        val arr = JsonArray()
        for (a in computeAxes(p)) {
            arr.add(JsonObject().apply {
                addProperty("key", a.key)
                addProperty("unit", a.unit)
                addProperty("value", roundForWire(a.value))
                addProperty("percent", round2(a.percent))
            })
        }
        return arr
    }

    /**
     * Walk every player known to the server (online + offline) and return
     * name → score for [metricKey]. The score is the raw `value` for that axis
     * (not the percent) so the leaderboard reflects actual achievement.
     */
    fun computeLeaderboard(metricKey: String): List<Pair<String, Double>> {
        if (metricKey !in AXIS_KEYS) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Pair<String, Double>>()
        for (p in Bukkit.getOnlinePlayers()) {
            val n = p.name
            if (seen.add(n)) {
                val axes = computeAxes(p)
                val v = axes.firstOrNull { it.key == metricKey }?.value ?: 0.0
                if (v > 0) result += n to v
            }
        }
        for (p in Bukkit.getOfflinePlayers()) {
            val n = p.name ?: continue
            if (seen.add(n)) {
                val axes = computeAxes(p)
                val v = axes.firstOrNull { it.key == metricKey }?.value ?: 0.0
                if (v > 0) result += n to v
            }
        }
        return result
    }

    private fun readUntyped(p: OfflinePlayer, s: Statistic): Long = try {
        p.getStatistic(s).toLong()
    } catch (_: Exception) {
        0L
    }

    private fun sumTyped(p: OfflinePlayer, s: Statistic, materials: List<Material>): Long {
        var sum = 0L
        for (m in materials) {
            try { sum += p.getStatistic(s, m).toLong() } catch (_: Exception) { /* ignore mismatched type */ }
        }
        return sum
    }

    private fun roundForWire(v: Double): Number {
        // walk/play_time/blocks/kills are conceptually integers — emit Long to
        // avoid "27781.0" noise. survival_index keeps two decimals.
        return if (v >= 0 && v % 1.0 == 0.0) v.toLong() else round2(v)
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
