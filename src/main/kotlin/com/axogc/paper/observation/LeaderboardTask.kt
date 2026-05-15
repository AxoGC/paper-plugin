package com.axogc.paper.observation

import com.axogc.paper.stats.StatsCollector
import com.axogc.paper.transport.ApiClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Periodic full-rebuild leaderboard push (plan §10.2).
 *
 * core's `leaderboard.update` handler atomically rebuilds the Redis ZSET for the
 * supplied metric, so we send one POST per metric. The whitelist comes from
 * [StatsCollector.METRICS] — a small set (~13 entries) so per-tick cost is bounded.
 */
class LeaderboardTask(private val api: ApiClient) : Runnable {

    /** Metrics actually pushed. Subset of [StatsCollector.METRICS]; tune as needed. */
    private val pushed: List<String> = listOf(
        "play_time",
        "mob_kills_total",
        "pvp_kills",
        "deaths",
        "damage_dealt",
        "blocks_broken" // exists in StatsCollector? — no, but ignored gracefully
    ).filter { it in StatsCollector.METRICS }

    override fun run() {
        for (metric in pushed) {
            val entries = StatsCollector.computeLeaderboard(metric)
            if (entries.isEmpty()) continue
            val arr = JsonArray()
            for ((name, score) in entries) {
                arr.add(JsonObject().apply {
                    addProperty("name", name)
                    addProperty("score", score)
                })
            }
            val body = JsonObject().apply {
                addProperty("metric", metric)
                add("entries", arr)
            }
            api.post("leaderboard.update", body)
        }
    }
}
