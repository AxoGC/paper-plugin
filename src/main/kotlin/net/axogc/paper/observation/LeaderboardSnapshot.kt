package net.axogc.paper.observation

import net.axogc.paper.stats.StatsCollector
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory sorted snapshot per metric, rebuilt periodically.
 *
 * Pull-mode leaderboard (PLAN §10.6): core asks `leaderboard.fetch` via the
 * long-poll bridge and we slice [snapshot] for a single metric. The rebuild
 * walks online + known-offline players (the previous push-mode cadence — see
 * [StatsCollector.computeLeaderboard]) and stores the full per-metric ranking
 * capped at [MAX_ENTRIES]. The snapshot ref swap is atomic so a fetch never
 * sees a half-rebuilt list.
 *
 * Rebuild cost is bounded: 6 axes × N players × ~100 Statistic reads each.
 * On a server with 200 known players that's ~120k Statistic calls every
 * rebuild interval (configurable via `leaderboard_ticks`, default 5 min).
 */
object LeaderboardSnapshot {

    private const val MAX_ENTRIES = 200

    data class Entry(val name: String, val score: Double)

    private val snapshot = AtomicReference<Map<String, List<Entry>>>(emptyMap())

    /** Walk all axes and rebuild the per-metric sorted list. */
    fun rebuild() {
        val next = HashMap<String, List<Entry>>(StatsCollector.AXIS_KEYS.size)
        for (metric in StatsCollector.AXIS_KEYS) {
            val rows = StatsCollector.computeLeaderboard(metric)
            val sorted = rows
                .sortedByDescending { it.second }
                .take(MAX_ENTRIES)
                .map { Entry(it.first, it.second) }
            next[metric] = sorted
        }
        snapshot.set(next)
    }

    /** Top [limit] entries for [metric], or empty if we haven't rebuilt yet. */
    fun slice(metric: String, limit: Int): List<Entry> {
        val list = snapshot.get()[metric] ?: return emptyList()
        if (limit <= 0) return emptyList()
        return if (list.size <= limit) list else list.subList(0, limit)
    }
}
