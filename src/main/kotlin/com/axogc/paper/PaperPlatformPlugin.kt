package com.axogc.paper

import com.axogc.paper.commands.BindCommand
import com.axogc.paper.commands.DocsCommand
import com.axogc.paper.commands.WebCommand
import com.axogc.paper.config.PluginConfig
import com.axogc.paper.handlers.CommandRouter
import com.axogc.paper.observation.HeartbeatTask
import com.axogc.paper.observation.LeaderboardTask
import com.axogc.paper.observation.PlayerListener
import com.axogc.paper.transport.ApiClient
import com.axogc.paper.transport.PollLoop
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Entry point.
 *
 * Lifecycle:
 *  - onEnable: load config → wire components → register listeners/commands → start poll loop + schedulers
 *  - onDisable: stop poll loop, cancel scheduled tasks
 *
 * Threading:
 *  - PollLoop runs on its own daemon thread.
 *  - HeartbeatTask + LeaderboardTask scheduled via Bukkit async scheduler (off main).
 *  - All Bukkit-API touching code from those threads hops back to main via scheduler.runTask.
 */
class PaperPlatformPlugin : JavaPlugin() {

    private var pollLoop: PollLoop? = null
    private var heartbeatTask: BukkitTask? = null
    private var leaderboardTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        val cfg = PluginConfig.load(config)

        if (cfg.token.isBlank() || cfg.token == "REPLACE_ME") {
            logger.severe("[platform] token not configured — edit plugins/PaperPlatform/config.yml")
            server.pluginManager.disablePlugin(this)
            return
        }
        if (cfg.baseUrl.isBlank()) {
            logger.severe("[platform] base_url not configured")
            server.pluginManager.disablePlugin(this)
            return
        }

        val api = ApiClient(cfg, logger)
        val router = CommandRouter(this, api, logger)

        // Poll loop — feed every event into the router on the same poll thread.
        // Router decides whether to hop to main; replies POST from this thread.
        pollLoop = PollLoop(api, logger) { evt -> router.dispatch(evt) }.also { it.start() }

        // Listeners
        server.pluginManager.registerEvents(PlayerListener(this, api, cfg), this)

        // Commands (declared in paper-plugin.yml)
        getCommand("bind")?.setExecutor(BindCommand(this, api))
        getCommand("web")?.setExecutor(WebCommand(cfg))
        getCommand("docs")?.setExecutor(DocsCommand(this, cfg))

        // Async periodic tasks — first tick = 1 (don't fire at tick 0 before world is ready)
        heartbeatTask = server.scheduler.runTaskTimerAsynchronously(
            this, HeartbeatTask(api), 1L, cfg.heartbeatTicks
        )
        leaderboardTask = server.scheduler.runTaskTimerAsynchronously(
            this, LeaderboardTask(api), cfg.leaderboardTicks, cfg.leaderboardTicks
        )

        logger.info("[platform] enabled — base=${cfg.baseUrl}, heartbeat=${cfg.heartbeatTicks}t, leaderboard=${cfg.leaderboardTicks}t")
    }

    override fun onDisable() {
        pollLoop?.stop()
        pollLoop = null
        heartbeatTask?.cancel()
        heartbeatTask = null
        leaderboardTask?.cancel()
        leaderboardTask = null
        logger.info("[platform] disabled")
    }
}
