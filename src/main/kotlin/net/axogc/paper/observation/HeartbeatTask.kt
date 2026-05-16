package net.axogc.paper.observation

import net.axogc.paper.transport.ApiClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bukkit.Bukkit

/**
 * Async timer that POSTs to /api/srv/heartbeat (plan §4.5, §15.1).
 * Refreshes Redis TTLs for server:status / online / max — must run at least every 60s,
 * defaulting to 30s.
 *
 * Reads Bukkit.getOnlinePlayers() from an async thread. The collection returned by
 * Paper is a defensive copy so this is safe; we don't touch individual Player APIs.
 */
class HeartbeatTask(private val api: ApiClient) : Runnable {
    override fun run() {
        val online = Bukkit.getOnlinePlayers()
        val players = JsonArray()
        for (p in online) players.add(p.name)

        val body = JsonObject().apply {
            addProperty("online", online.size)
            addProperty("max", Bukkit.getMaxPlayers())
            add("players", players)
        }
        api.post("heartbeat", body)
    }
}
