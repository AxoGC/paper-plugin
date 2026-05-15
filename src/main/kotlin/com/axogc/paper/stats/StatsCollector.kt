package com.axogc.paper.stats

import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.Statistic

/**
 * Reads Bukkit Statistic API into a flat JSON map suitable for `player.stats.fetch`
 * reply payloads and `leaderboard.update` aggregation (plan §10.2).
 *
 * Only "untyped" statistics are read here — typed ones (MINE_BLOCK by Material etc.)
 * are skipped to avoid a per-call O(materials) scan. PK radar values pull from this
 * same set (plan §10.4) so adding a new axis = adding a row here.
 */
object StatsCollector {

    /**
     * Plain-name → Bukkit Statistic mapping.
     * Names match the Bedrock white-list vocabulary in plan §10.3 so the frontend
     * radar chart can render Java and Bedrock players with the same indicator set.
     *
     * Units:
     *  - distance_* are in centimetres (Bukkit native)
     *  - play_time is in ticks (Bukkit native, 20 ticks = 1 second)
     */
    private val FIELDS: List<Pair<String, Statistic>> = listOf(
        "play_time"        to Statistic.PLAY_ONE_MINUTE, // misnamed in Bukkit — actually ticks
        "deaths"           to Statistic.DEATHS,
        "mob_kills_total"  to Statistic.MOB_KILLS,
        "pvp_kills"        to Statistic.PLAYER_KILLS,
        "damage_dealt"     to Statistic.DAMAGE_DEALT,
        "damage_taken"     to Statistic.DAMAGE_TAKEN,
        "distance_walked"  to Statistic.WALK_ONE_CM,
        "distance_sprinted" to Statistic.SPRINT_ONE_CM,
        "distance_flown"   to Statistic.AVIATE_ONE_CM,
        "jumps"            to Statistic.JUMP,
        "items_dropped"    to Statistic.DROP_COUNT,
        "fish_caught"      to Statistic.FISH_CAUGHT,
        "animals_bred"     to Statistic.ANIMALS_BRED,
    )

    /** All metric names exposed to the leaderboard / PK frontend. */
    val METRICS: Set<String> = FIELDS.map { it.first }.toSet()

    /**
     * Read one (online or offline) player's stats as a JsonObject.
     *
     * Safe to call off-main for OfflinePlayer — Bukkit caches per-player NBT and
     * blocking disk reads are tolerable on the long-poll worker thread.
     */
    fun read(player: OfflinePlayer): JsonObject {
        val obj = JsonObject()
        for ((name, stat) in FIELDS) {
            val v = try { player.getStatistic(stat).toLong() } catch (e: Exception) { 0L }
            obj.addProperty(name, v)
        }
        return obj
    }

    /** Read a single metric value for [player]. Returns 0 if not in [METRICS]. */
    fun readMetric(player: OfflinePlayer, metric: String): Double {
        val stat = FIELDS.firstOrNull { it.first == metric }?.second ?: return 0.0
        return try { player.getStatistic(stat).toDouble() } catch (e: Exception) { 0.0 }
    }

    /**
     * Walk every player known to the server (online + offline) and return name→score for [metric].
     * Used by the periodic leaderboard recompute (plan §10.2 "全量重建").
     */
    fun computeLeaderboard(metric: String): List<Pair<String, Double>> {
        if (metric !in METRICS) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Pair<String, Double>>()
        for (p in Bukkit.getOnlinePlayers()) {
            val n = p.name ?: continue
            if (seen.add(n)) result += n to readMetric(p, metric)
        }
        for (p in Bukkit.getOfflinePlayers()) {
            val n = p.name ?: continue
            if (seen.add(n)) result += n to readMetric(p, metric)
        }
        return result
    }
}
