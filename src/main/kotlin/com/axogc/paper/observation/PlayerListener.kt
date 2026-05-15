package com.axogc.paper.observation

import com.axogc.paper.config.PluginConfig
import com.axogc.paper.transport.ApiClient
import com.google.gson.JsonObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin

/**
 * Player join/quit → upstream events (plan §4.5).
 * Also shows the welcome banner on join (plan §15.2 亮点功能) when enabled.
 */
class PlayerListener(
    private val plugin: Plugin,
    private val api: ApiClient,
    private val cfg: PluginConfig,
) : Listener {

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        val name = player.name
        val uuid = player.uniqueId.toString()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val body = JsonObject().apply {
                addProperty("name", name)
                addProperty("external_id", uuid)
            }
            api.post("player.joined", body)
        })

        if (cfg.welcomeBanner) {
            // Adventure messages — must be on main thread; PlayerJoinEvent is already on main.
            player.sendMessage(
                Component.text("欢迎回来，", NamedTextColor.GRAY)
                    .append(Component.text(name, NamedTextColor.AQUA))
                    .append(Component.text("！", NamedTextColor.GRAY))
            )
            player.sendMessage(
                Component.text("输入 ", NamedTextColor.GRAY)
                    .append(Component.text("/docs", NamedTextColor.GOLD))
                    .append(Component.text(" 查看文档，", NamedTextColor.GRAY))
                    .append(Component.text("/bind <CODE>", NamedTextColor.GOLD))
                    .append(Component.text(" 绑定账号。", NamedTextColor.GRAY))
            )
        }
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val name = e.player.name
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val body = JsonObject().apply { addProperty("name", name) }
            api.post("player.left", body)
        })
    }
}
