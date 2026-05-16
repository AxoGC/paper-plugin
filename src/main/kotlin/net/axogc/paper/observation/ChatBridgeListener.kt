package net.axogc.paper.observation

import net.axogc.paper.transport.ApiClient
import com.google.gson.JsonObject
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

/**
 * Mirrors in-game chat to the platform via POST /api/srv/chat.message.
 *
 * Constraints (plan §17.7):
 *  - Does NOT cancel or rewrite the event — Paper's own broadcast remains the
 *    authoritative in-game distribution.
 *  - Strips formatting/control chars before sending to web by going through
 *    PlainTextComponentSerializer.
 *  - Skips messages that look like commands (start with `/`).
 *  - Posts on the async scheduler since AsyncChatEvent already fires off-main,
 *    but we still avoid touching Bukkit API from the HTTP call.
 */
class ChatBridgeListener(
    private val plugin: Plugin,
    private val api: ApiClient,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(e: AsyncChatEvent) {
        val name = e.player.name
        val raw = PlainTextComponentSerializer.plainText().serialize(e.message())
        val cleaned = sanitize(raw)
        if (cleaned.isEmpty()) return
        if (cleaned.startsWith("/")) return

        // Already on an async thread (AsyncChatEvent), but use scheduler to detach
        // from event delivery — keeps the event chain short and avoids surprises.
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val body = JsonObject().apply {
                addProperty("sender", name)
                addProperty("content", cleaned)
            }
            api.post("chat.message", body)
        })
    }

    private fun sanitize(s: String): String {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            when {
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                ch.code < 0x20 || ch.code == 0x7f -> { /* drop */ }
                ch == '§' -> { /* drop legacy color sigil */ }
                else -> sb.append(ch)
            }
        }
        // Clamp to 500 chars to match core's MaxContentLen.
        var out = sb.toString().trim()
        if (out.length > 500) out = out.substring(0, 500)
        return out
    }
}
