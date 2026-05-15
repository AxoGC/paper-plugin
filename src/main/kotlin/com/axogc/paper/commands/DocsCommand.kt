package com.axogc.paper.commands

import com.axogc.paper.config.PluginConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/docs [path]` — browse markdown docs stored under the plugin data folder (plan §15.2).
 *
 * Layout (filesystem-backed): `<plugin-data>/<docs_dir>/...`.
 * The command lists subdirectories and previews leaf .md files inline.
 * Conversion is intentionally minimal — heading and link recognition only — because
 * the same source files render in VitePress (plan §15.2 "从 VitePress 同源 Markdown 加载").
 */
class DocsCommand(
    private val plugin: Plugin,
    private val cfg: PluginConfig,
) : CommandExecutor {

    private val root: Path get() = plugin.dataFolder.toPath().resolve(cfg.docsDir)

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (!Files.isDirectory(root)) {
            sender.sendMessage(Component.text("文档尚未配置（${root}）。", NamedTextColor.YELLOW))
            return true
        }
        val rel = args.joinToString("/").trim('/')
        // Resolve safely — refuse anything that escapes the docs root.
        val resolved = root.resolve(rel).normalize()
        if (!resolved.startsWith(root)) {
            sender.sendMessage(Component.text("非法路径。", NamedTextColor.RED))
            return true
        }

        when {
            Files.isDirectory(resolved) -> listDir(sender, resolved)
            Files.isRegularFile(resolved) && resolved.toString().endsWith(".md") -> printFile(sender, resolved)
            Files.exists(resolved) -> sender.sendMessage(Component.text("不是文档: $rel", NamedTextColor.RED))
            else -> sender.sendMessage(Component.text("未找到: $rel", NamedTextColor.RED))
        }
        return true
    }

    private fun listDir(sender: CommandSender, dir: Path) {
        val title = if (dir == root) "/" else "/" + root.relativize(dir).toString().replace('\\', '/')
        sender.sendMessage(Component.text("== 文档: $title ==", NamedTextColor.GOLD))
        val entries = Files.newDirectoryStream(dir).use { it.toList() }.sorted()
        for (e in entries) {
            val name = e.fileName.toString()
            if (Files.isDirectory(e)) {
                val rel = root.relativize(e).toString().replace('\\', '/')
                sender.sendMessage(
                    Component.text("▸ $name/", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/docs $rel"))
                )
            } else if (name.endsWith(".md")) {
                val rel = root.relativize(e).toString().replace('\\', '/')
                val display = name.removeSuffix(".md")
                sender.sendMessage(
                    Component.text("• $display", NamedTextColor.WHITE)
                        .clickEvent(ClickEvent.runCommand("/docs $rel"))
                )
            }
        }
    }

    private fun printFile(sender: CommandSender, file: Path) {
        val rel = root.relativize(file).toString().replace('\\', '/')
        sender.sendMessage(Component.text("== $rel ==", NamedTextColor.GOLD))
        val lines = Files.readAllLines(file)
        // Cap output to ~80 lines to avoid chat flood; deeper reading goes to the web.
        val max = 80
        for ((i, line) in lines.withIndex()) {
            if (i >= max) {
                sender.sendMessage(Component.text("... (truncated, ${lines.size - max} more lines)", NamedTextColor.GRAY))
                break
            }
            sender.sendMessage(formatLine(line))
        }
    }

    private fun formatLine(raw: String): Component {
        // Minimal styling: heading prefix = gold, list bullets passthrough, blank line passthrough.
        if (raw.startsWith("# ")) return Component.text(raw.substring(2), NamedTextColor.GOLD)
        if (raw.startsWith("## ")) return Component.text(raw.substring(3), NamedTextColor.YELLOW)
        if (raw.startsWith("### ")) return Component.text(raw.substring(4), NamedTextColor.GREEN)
        return Component.text(raw, NamedTextColor.WHITE)
    }
}
