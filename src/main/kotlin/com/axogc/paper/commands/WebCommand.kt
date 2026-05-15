package com.axogc.paper.commands

import com.axogc.paper.config.PluginConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

/** `/web` — show a clickable platform URL (plan §15.2 亮点功能). */
class WebCommand(private val cfg: PluginConfig) : CommandExecutor {
    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        val url = cfg.webUrl.ifBlank { cfg.baseUrl }
        if (url.isBlank()) {
            sender.sendMessage(Component.text("未配置网站地址。", NamedTextColor.RED))
            return true
        }
        sender.sendMessage(
            Component.text("社区网站: ", NamedTextColor.GRAY)
                .append(
                    Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                )
        )
        return true
    }
}
