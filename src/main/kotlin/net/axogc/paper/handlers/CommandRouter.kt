package net.axogc.paper.handlers

import net.axogc.paper.observation.LeaderboardSnapshot
import net.axogc.paper.stats.StatsCollector
import net.axogc.paper.transport.ApiClient
import net.axogc.paper.transport.IncomingEvent
import net.axogc.paper.transport.ReplyBody
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * Dispatches downstream events to handlers.
 *
 * Threading: invoked on the poll-loop thread. Most handlers either run pure
 * computation (safe) or hop to the main thread via Bukkit scheduler for
 * Player API calls. Replies are POSTed from the poll thread to avoid main-thread HTTP.
 *
 * Reply discipline (plan §4.5):
 *  - kick / notify / broadcast: fire-and-forget, no reply
 *  - whitelist.add / whitelist.remove: reply {added|removed: bool}
 *  - stats.fetch: reply {stats: object}
 *  - unknown command: reply with COMMAND_UNKNOWN so core can stop waiting (if it was)
 */
class CommandRouter(
    private val plugin: Plugin,
    private val api: ApiClient,
    private val log: Logger,
) {
    private val adminCommand = AdminCommandHandler(plugin, api, log)

    fun dispatch(event: IncomingEvent) {
        when (event.command) {
            "player.kick"             -> handleKick(event)
            "player.notify"           -> handleNotify(event)
            "player.whitelist.add"    -> handleWhitelistAdd(event)
            "player.whitelist.remove" -> handleWhitelistRemove(event)
            "player.stats.fetch"      -> handleStatsFetch(event)
            "metrics.list"            -> handleMetricsList(event)
            "leaderboard.fetch"       -> handleLeaderboardFetch(event)
            "server.broadcast"        -> handleBroadcast(event)
            "chat.from_web"           -> handleChatFromWeb(event)
            "admin.command.run"       -> adminCommand.handle(event)
            else -> {
                log.warning("[platform] unknown command: ${event.command}")
                api.reply(ReplyBody.fail(event.id, "COMMAND_UNKNOWN"))
            }
        }
    }

    // -------- handlers --------

    private fun handleKick(e: IncomingEvent) {
        val name = e.data.stringOrNull("name") ?: return
        val reason = e.data.stringOrNull("reason") ?: "Kicked by platform"
        runSync {
            Bukkit.getPlayerExact(name)?.kick(Component.text(reason))
        }
    }

    private fun handleNotify(e: IncomingEvent) {
        val name = e.data.stringOrNull("name") ?: return
        val msg = e.data.stringOrNull("message") ?: return
        runSync {
            Bukkit.getPlayerExact(name)?.sendMessage(Component.text(msg))
        }
    }

    private fun handleBroadcast(e: IncomingEvent) {
        val msg = e.data.stringOrNull("message") ?: return
        runSync {
            Bukkit.getServer().sendMessage(Component.text(msg))
        }
    }

    /**
     * `chat.from_web` (fire-and-forget): a web user posted in this server's
     * channel. Broadcast `[Web] <sender> <content>` in-game without re-emitting
     * upstream (avoids feedback loop).
     */
    private fun handleChatFromWeb(e: IncomingEvent) {
        val sender = e.data.stringOrNull("sender") ?: return
        val content = e.data.stringOrNull("content") ?: return
        if (sender.isBlank() || content.isBlank()) return
        val line = Component.text("[Web] ", NamedTextColor.AQUA)
            .append(Component.text(sender, NamedTextColor.GOLD))
            .append(Component.text(" ", NamedTextColor.GRAY))
            .append(Component.text(content, NamedTextColor.WHITE))
        runSync {
            Bukkit.getServer().sendMessage(line)
        }
    }

    private fun handleWhitelistAdd(e: IncomingEvent) {
        val name = e.data.stringOrNull("name") ?: run {
            api.reply(ReplyBody.fail(e.id, "REQUEST_INVALID"))
            return
        }
        runSyncReturning {
            val op: OfflinePlayer = Bukkit.getOfflinePlayer(name)
            val already = op.isWhitelisted
            if (!already) op.isWhitelisted = true
            JsonObject().apply { addProperty("added", !already) }
        }.thenAccept { data -> api.reply(ReplyBody.ok(e.id, data)) }
    }

    private fun handleWhitelistRemove(e: IncomingEvent) {
        val name = e.data.stringOrNull("name") ?: run {
            api.reply(ReplyBody.fail(e.id, "REQUEST_INVALID"))
            return
        }
        runSyncReturning {
            val op: OfflinePlayer = Bukkit.getOfflinePlayer(name)
            val was = op.isWhitelisted
            if (was) op.isWhitelisted = false
            JsonObject().apply { addProperty("removed", was) }
        }.thenAccept { data -> api.reply(ReplyBody.ok(e.id, data)) }
    }

    /**
     * `metrics.list`: return our static axis-metadata table verbatim. core
     * caches the response for ~1h, so this should run in microseconds.
     */
    private fun handleMetricsList(e: IncomingEvent) {
        val payload = JsonObject().apply { add("metrics", StatsCollector.metricsPayload()) }
        api.reply(ReplyBody.ok(e.id, payload))
    }

    /**
     * `leaderboard.fetch`: slice the in-memory [LeaderboardSnapshot] for one
     * metric. Snapshot is rebuilt by the async LeaderboardTask on the plugin's
     * own cadence; here we just slice — sub-millisecond.
     */
    private fun handleLeaderboardFetch(e: IncomingEvent) {
        val metric = e.data.stringOrNull("metric") ?: run {
            api.reply(ReplyBody.fail(e.id, "REQUEST_INVALID"))
            return
        }
        val limit = (e.data.get("limit")?.takeIf { !it.isJsonNull }?.asInt ?: 50)
            .coerceIn(1, 200)
        val arr = JsonArray()
        for (entry in LeaderboardSnapshot.slice(metric, limit)) {
            arr.add(JsonObject().apply {
                addProperty("name", entry.name)
                addProperty("score", entry.score)
            })
        }
        val payload = JsonObject().apply { add("entries", arr) }
        api.reply(ReplyBody.ok(e.id, payload))
    }

    private fun handleStatsFetch(e: IncomingEvent) {
        val name = e.data.stringOrNull("name") ?: run {
            api.reply(ReplyBody.fail(e.id, "REQUEST_INVALID"))
            return
        }
        // OfflinePlayer.getStatistic reads from the player NBT file — keep off main.
        // The poll thread already is off-main, so a direct call is fine.
        val op = Bukkit.getOfflinePlayer(name)
        if (!op.hasPlayedBefore() && !op.isOnline) {
            api.reply(ReplyBody.fail(e.id, "PLAYER_NOT_FOUND"))
            return
        }
        val axes = StatsCollector.computeAxesJson(op)
        val payload = JsonObject().apply { add("stats", axes) }
        api.reply(ReplyBody.ok(e.id, payload))
    }

    // -------- scheduler helpers --------

    private fun runSync(block: () -> Unit) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try { block() } catch (t: Throwable) { log.warning("[platform] sync task failed: ${t.message}") }
        })
    }

    /** Run [block] on the main thread and return a CompletableFuture with its result. */
    private fun <T> runSyncReturning(block: () -> T): CompletableFuture<T> {
        val fut = CompletableFuture<T>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try { fut.complete(block()) } catch (t: Throwable) { fut.completeExceptionally(t) }
        })
        return fut
    }
}

private fun JsonObject.stringOrNull(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return try { el.asString } catch (e: Exception) { null }
}
