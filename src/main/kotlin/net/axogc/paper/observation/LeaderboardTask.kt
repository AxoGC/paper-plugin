package net.axogc.paper.observation

import net.axogc.paper.stats.StatsCollector
import net.axogc.paper.transport.ApiClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Periodic full-rebuild leaderboard push.
 *
 * core's `leaderboard.update` handler atomically rebuilds the Redis ZSET for the
 * supplied metric, so we send one POST per axis. The axis set comes from
 * [StatsCollector.AXIS_KEYS] — six entries, bounded per-tick cost.
 */
class LeaderboardTask(private val api: ApiClient) : Runnable {

    override fun run() {
        for (metric in StatsCollector.AXIS_KEYS) {
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
