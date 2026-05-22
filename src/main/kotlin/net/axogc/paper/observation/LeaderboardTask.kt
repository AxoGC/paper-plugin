package net.axogc.paper.observation

/**
 * Periodically rebuilds the in-memory [LeaderboardSnapshot]. Pulled by core
 * via the `leaderboard.fetch` command (PLAN §10.6); replaces the prior
 * push-mode loop that POSTed `/api/srv/leaderboard.update` per axis.
 *
 * Scheduled via the Bukkit async scheduler (see PaperPlatformPlugin), so the
 * Statistic API reads happen off main. Rebuild cost is bounded — see comment
 * on [LeaderboardSnapshot.rebuild].
 */
class LeaderboardTask : Runnable {
    override fun run() {
        LeaderboardSnapshot.rebuild()
    }
}
