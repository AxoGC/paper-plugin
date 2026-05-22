package net.axogc.paper.stats

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import java.io.File
import java.util.UUID

/**
 * Derives the six unified radar axes by reading world/stats/<uuid>.json directly
 * (vanilla player stats file):
 *  - walk_total_m   = (walk_one_cm + sprint_one_cm) / 100, integer meters
 *  - play_time      = play_time ticks / 20, integer seconds
 *  - survival_index = play_time_s / (deaths + 30)
 *  - blocks_placed  = Σ minecraft:used over block-form items
 *  - blocks_broken  = Σ minecraft:mined (all entries are blocks)
 *  - kills_total    = mob_kills + player_kills
 *
 * The order of [AXIS_KEYS] is the wire order and the radar axis order — do not
 * reshuffle without coordinating with the web side.
 *
 * `percent` is the value normalized against a fixed scale (see [SCALES]).
 * Scales are empirical, not subjective: they are the p95 of active players
 * (>=30 min play time) across three long-running survival worlds — neo-paper,
 * paper, old-paper, n=188 active out of 307 total — rounded up to the next
 * 1/2/5*10^k tier. Reasoning: p95 means a top-5% player fills the radar, the
 * truly elite naturally overflow past 100% (which the chart treats as the
 * "legendary" tier), and the rest of the active community still gets visible
 * relative position. See docs/SCALES.md for the raw distribution and the
 * Python derivation. Re-derive when the player base shifts materially; the
 * protocol carries percent so core/web never need to know the scale at all.
 */
object StatsCollector {

    /**
     * Plugin-owned axis metadata. See PLAN §10.6 / the DST mod axes.lua for
     * the contract — `metrics.list` reply ships this verbatim so core/web
     * stay metric-agnostic and a future axis tweak is a plugin-only change.
     */
    data class Metric(
        val key: String,
        val unit: String,
        val labelZh: String,
        val labelEn: String,
        val scale: Double,
        val order: Int,
        val champion: Boolean,
    )

    val METRICS: List<Metric> = listOf(
        Metric("walk_total_m",   "m", "移动距离",     "Distance",         500_000.0, 1, true),
        Metric("play_time",      "s", "在线时长",     "Play Time",      1_000_000.0, 2, true),
        Metric("survival_index", "",  "存活指数",     "Survival Index",     5_000.0, 3, true),
        Metric("blocks_placed",  "",  "放置方块",     "Blocks Placed",    100_000.0, 4, true),
        Metric("blocks_broken",  "",  "破坏方块",     "Blocks Broken",    200_000.0, 5, true),
        Metric("kills_total",    "",  "击杀数",       "Kills",             50_000.0, 6, true),
    )

    val AXIS_KEYS: List<String> = METRICS.map { it.key }

    private val UNITS: Map<String, String> = METRICS.associate { it.key to it.unit }
    private val SCALES: Map<String, Double> = METRICS.associate { it.key to it.scale }

    /** Wire form for `metrics.list` reply. */
    fun metricsPayload(): JsonArray {
        val arr = JsonArray()
        for (m in METRICS) {
            arr.add(JsonObject().apply {
                addProperty("key", m.key)
                addProperty("unit", m.unit)
                addProperty("label_zh", m.labelZh)
                addProperty("label_en", m.labelEn)
                addProperty("scale", m.scale)
                addProperty("order", m.order)
                addProperty("champion", m.champion)
            })
        }
        return arr
    }

    // Namespaced IDs of block-form items, matching keys under `minecraft:used` in
    // world/stats/<uuid>.json. We sum USE_ITEM only over block-form items (not
    // tools, food, etc.) so `blocks_placed` reflects actual block placement.
    private val BLOCK_ITEM_KEYS: Set<String> = Material.values()
        .filter { it.isItem && it.isBlock }
        .map { it.key.toString() }
        .toSet()

    data class Axis(val key: String, val unit: String, val value: Double, val percent: Double)

    /** Compute all six axes for one player. */
    fun computeAxes(p: OfflinePlayer): List<Axis> {
        // Single path for online + offline: read world/stats/<uuid>.json directly.
        // The previous dual-path approach (Bukkit Statistic API for online, JSON
        // for offline) silently drifted whenever Mojang renamed a stat key —
        // Bukkit translates internally, our manual key lookup didn't. Reading
        // the JSON keeps the two cases byte-identical. Online freshness lag is
        // bounded by auto-save (~5 min) and dominated by core's 1 h stats cache.
        // Missing file → never-saved fresh player → all-zero axes.
        val json = loadStatsJson(p.uniqueId) ?: return buildAxes(0.0, 0.0, 0L, 0L, 0L, 0L)
        return computeFromJson(json)
    }

    private fun computeFromJson(root: JsonObject): List<Axis> {
        val stats = root.getAsJsonObject("stats") ?: JsonObject()
        val custom = stats.getAsJsonObject("minecraft:custom") ?: JsonObject()
        val mined = stats.getAsJsonObject("minecraft:mined") ?: JsonObject()
        val used = stats.getAsJsonObject("minecraft:used") ?: JsonObject()

        val walkCm = customLong(custom, "minecraft:walk_one_cm") +
            customLong(custom, "minecraft:sprint_one_cm")
        val walkM = walkCm / 100.0
        // Note: vanilla key is `play_time` since MC 1.17. Bukkit kept its
        // PLAY_ONE_MINUTE enum name for source-compat but the on-disk key changed.
        val playTimeS = customLong(custom, "minecraft:play_time") / 20.0
        val deaths = customLong(custom, "minecraft:deaths")

        // mined: every key is a block, sum all entries directly.
        var broken = 0L
        for ((_, v) in mined.entrySet()) {
            broken += longOrZero(v)
        }
        // used: keys can be non-blocks (tools, food) too; only count block-form items.
        var placed = 0L
        for ((k, v) in used.entrySet()) {
            if (k in BLOCK_ITEM_KEYS) placed += longOrZero(v)
        }
        val kills = customLong(custom, "minecraft:mob_kills") +
            customLong(custom, "minecraft:player_kills")
        return buildAxes(walkM, playTimeS, deaths, placed, broken, kills)
    }

    private fun buildAxes(
        walkM: Double, playTimeS: Double, deaths: Long,
        placed: Long, broken: Long, kills: Long,
    ): List<Axis> {
        val survival = playTimeS / (deaths + 30.0)
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

    private fun customLong(custom: JsonObject, key: String): Long =
        longOrZero(custom.get(key))

    private fun longOrZero(v: com.google.gson.JsonElement?): Long {
        if (v == null || v.isJsonNull) return 0L
        return try { v.asLong } catch (_: Exception) { 0L }
    }

    private fun loadStatsJson(uuid: UUID): JsonObject? {
        val world = Bukkit.getWorlds().firstOrNull() ?: return null
        val file = File(world.worldFolder, "stats/$uuid.json")
        if (!file.exists()) return null
        return try {
            file.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        } catch (_: Exception) {
            null
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

    private fun roundForWire(v: Double): Number {
        // walk/play_time/blocks/kills are conceptually integers — emit Long to
        // avoid "27781.0" noise. survival_index keeps two decimals.
        return if (v >= 0 && v % 1.0 == 0.0) v.toLong() else round2(v)
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
