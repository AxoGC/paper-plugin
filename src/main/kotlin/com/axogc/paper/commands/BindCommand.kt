package com.axogc.paper.commands

import com.axogc.paper.transport.ApiClient
import com.google.gson.JsonObject
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `/bind <CODE>` — sends POST /api/srv/binding.request (plan §9).
 *
 * Server-side will:
 *  - validate the code (5min TTL, one-time)
 *  - check (server_id, game_name) free
 *  - write the Player record
 *  - send back `player.notify` with the result over /poll
 *
 * Per plan §9 "服务器插件侧：玩家 /bind 连续失败 3 次禁用 10 分钟" we maintain a
 * lightweight per-player throttle here.
 */
class BindCommand(
    private val plugin: Plugin,
    private val api: ApiClient,
) : CommandExecutor {

    private data class Bucket(val fails: AtomicInteger, var blockedUntilMs: Long)
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val cooldownMs = 10 * 60 * 1000L
    private val maxFails = 3

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("This command is in-game only.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("用法: /bind <验证码>", NamedTextColor.YELLOW))
            return true
        }
        val now = System.currentTimeMillis()
        val bucket = buckets.computeIfAbsent(sender.name) { Bucket(AtomicInteger(0), 0L) }
        if (bucket.blockedUntilMs > now) {
            val remain = (bucket.blockedUntilMs - now) / 1000
            sender.sendMessage(Component.text("绑定尝试过多，请 ${remain}s 后再试。", NamedTextColor.RED))
            return true
        }

        val code = args[0].uppercase().trim()
        if (code.length !in 4..16) {
            sender.sendMessage(Component.text("验证码格式不正确。", NamedTextColor.RED))
            return true
        }

        val name = sender.name
        val uuid = sender.uniqueId.toString()

        // Off-main: HTTP call
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val body = JsonObject().apply {
                addProperty("code", code)
                add("player", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("external_id", uuid)
                })
            }
            val data = api.post("binding.request", body)
            // Sync-back to send the feedback line.
            plugin.server.scheduler.runTask(plugin, Runnable {
                val online = sender.isOnline
                if (data != null) {
                    bucket.fails.set(0)
                    if (online) {
                        sender.sendMessage(
                            Component.text("已发起绑定，结果稍后通过聊天反馈。", NamedTextColor.GREEN)
                        )
                    }
                } else {
                    val n = bucket.fails.incrementAndGet()
                    if (n >= maxFails) {
                        bucket.blockedUntilMs = System.currentTimeMillis() + cooldownMs
                        bucket.fails.set(0)
                    }
                    if (online) {
                        sender.sendMessage(
                            Component.text("验证码无效或已过期。", NamedTextColor.RED)
                        )
                    }
                }
            })
        })
        return true
    }
}
