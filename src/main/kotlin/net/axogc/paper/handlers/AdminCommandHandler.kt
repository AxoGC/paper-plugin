package net.axogc.paper.handlers

import com.google.gson.JsonObject
import net.axogc.paper.transport.ApiClient
import net.axogc.paper.transport.IncomingEvent
import net.axogc.paper.transport.ReplyBody
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * Runs admin-side commands (kick / ban / whitelist add|remove / raw) coming
 * from core's admin console and replies with the captured console output.
 *
 * Why dispatch through Bukkit's command system rather than calling the typed
 * Paper APIs directly (Player.kick, BanList, etc.):
 *  - The plugin already lets typed and raw share the same output-capture path.
 *  - Behavior (broadcast to ops, permission messages, language) matches what
 *    admins see when typing on the console — least surprising.
 */
class AdminCommandHandler(
    private val plugin: Plugin,
    private val api: ApiClient,
    private val log: Logger,
) {
    fun handle(e: IncomingEvent) {
        val kind = e.data.stringOrNullX("kind")
        if (kind.isNullOrBlank()) {
            api.reply(ReplyBody.fail(e.id, "REQUEST_INVALID"))
            return
        }
        val target = e.data.stringOrNullX("target")?.trim().orEmpty()
        val reason = e.data.stringOrNullX("reason")?.trim().orEmpty()
        val raw = e.data.stringOrNullX("raw")?.trim().orEmpty()

        val cmd = buildCommand(kind, target, reason, raw)
        if (cmd == null) {
            api.reply(ReplyBody.fail(e.id, "COMMAND_BUILD_FAILED"))
            return
        }

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val captured = CapturingSender(Bukkit.getConsoleSender())
            var ok = false
            try {
                ok = Bukkit.dispatchCommand(captured, cmd)
            } catch (t: Throwable) {
                log.warning("[platform] admin command failed: ${t.message}")
                captured.appendLine("error: ${t.message}")
            }
            val out = captured.output().ifBlank { if (ok) "ok" else "no output" }
            val payload = JsonObject().apply {
                addProperty("ok", ok)
                addProperty("output", out)
                addProperty("dispatched", cmd)
            }
            api.reply(ReplyBody.ok(e.id, payload))
        })
    }

    /**
     * Translate a (kind, target, reason, raw) request into the exact console
     * command string. Returns null if the request is malformed for its kind.
     * Raw is dispatched as-is (leading `/` stripped); the rest assemble from
     * vanilla command syntax.
     */
    private fun buildCommand(kind: String, target: String, reason: String, raw: String): String? {
        return when (kind) {
            "raw" -> {
                if (raw.isBlank()) return null
                raw.removePrefix("/").trim().ifBlank { null }
            }
            "kick" -> {
                if (target.isBlank()) return null
                if (reason.isBlank()) "kick $target" else "kick $target $reason"
            }
            "ban" -> {
                if (target.isBlank()) return null
                if (reason.isBlank()) "ban $target" else "ban $target $reason"
            }
            "whitelist_add" -> {
                if (target.isBlank()) return null
                "whitelist add $target"
            }
            "whitelist_remove" -> {
                if (target.isBlank()) return null
                "whitelist remove $target"
            }
            else -> null
        }
    }
}

/**
 * CommandSender that mirrors a console sender for everything except message
 * delivery: lines fed via sendMessage(...) variants are also collected into a
 * buffer the caller can read after the command runs. We still forward to the
 * underlying console so the lines show up in the server log too.
 *
 * Kotlin's `by delegate` provides the rest of the (very wide) CommandSender
 * surface — permissions, op state, server lookup, etc.
 */
private class CapturingSender(
    private val console: ConsoleCommandSender,
) : CommandSender by console {
    private val buf = StringBuilder()

    fun output(): String = buf.toString().trim()

    fun appendLine(s: String) {
        val t = s.trim()
        if (t.isEmpty()) return
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(t)
    }

    override fun sendMessage(message: String) {
        appendLine(message)
        console.sendMessage(message)
    }

    override fun sendMessage(vararg messages: String) {
        for (m in messages) sendMessage(m)
    }

    override fun sendMessage(message: Component) {
        appendLine(PlainTextComponentSerializer.plainText().serialize(message))
        console.sendMessage(message)
    }
}

/** Local helper so we don't depend on the CommandRouter file's private ext. */
private fun JsonObject.stringOrNullX(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return try { el.asString } catch (_: Exception) { null }
}
