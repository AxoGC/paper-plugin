package net.axogc.paper.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/axo <sub> [...]` — single entry point that routes to bind / web / docs.
 *
 * The leaf executors (BindCommand / WebCommand / DocsCommand) are reused as-is.
 * Subcommands always see the trimmed args (i.e. `/axo bind ABCDEF` → BindCommand
 * receives `["ABCDEF"]`).
 *
 * Adding `/axo` as a single Bukkit command avoids namespace collisions with
 * other plugins that may already own `/bind` or `/web`.
 */
class AxoCommand(
    private val bind: BindCommand,
    private val web: WebCommand,
    private val docs: DocsCommand,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            help(sender)
            return true
        }
        val sub = args[0].lowercase()
        val rest = if (args.size > 1) args.copyOfRange(1, args.size) else emptyArray()
        return when (sub) {
            "bind" -> bind.onCommand(sender, cmd, "$label bind", rest)
            "web"  -> web.onCommand(sender, cmd, "$label web", rest)
            "docs" -> docs.onCommand(sender, cmd, "$label docs", rest)
            "help", "?" -> { help(sender); true }
            else -> {
                sender.sendMessage(Component.text("未知子命令: $sub", NamedTextColor.RED))
                help(sender)
                true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender, cmd: Command, alias: String, args: Array<out String>,
    ): List<String> {
        if (args.size <= 1) {
            val prefix = args.firstOrNull().orEmpty().lowercase()
            return SUBS.filter { it.startsWith(prefix) }
        }
        // Delegate deeper completion only for `docs` (file paths). bind/web don't need it.
        return emptyList()
    }

    private fun help(sender: CommandSender) {
        sender.sendMessage(Component.text("用法:", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("  /axo bind <验证码>", NamedTextColor.YELLOW)
            .append(Component.text("  绑定平台账号", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  /axo web", NamedTextColor.YELLOW)
            .append(Component.text("  显示社区网址", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("  /axo docs [path]", NamedTextColor.YELLOW)
            .append(Component.text("  浏览文档", NamedTextColor.GRAY)))
    }

    private companion object {
        val SUBS = listOf("bind", "web", "docs", "help")
    }
}
