package com.axogc.paper.config

import org.bukkit.configuration.file.FileConfiguration

data class PluginConfig(
    val baseUrl: String,
    val token: String,
    val pollTimeoutMs: Long,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val heartbeatTicks: Long,
    val leaderboardTicks: Long,
    val webUrl: String,
    val docsDir: String,
    val welcomeBanner: Boolean,
) {
    companion object {
        fun load(fc: FileConfiguration): PluginConfig {
            val base = fc.getString("base_url", "")!!.trimEnd('/')
            return PluginConfig(
                baseUrl = base,
                token = fc.getString("token", "")!!,
                pollTimeoutMs = fc.getLong("poll_timeout_ms", 35_000),
                connectTimeoutMs = fc.getLong("connect_timeout_ms", 5_000),
                requestTimeoutMs = fc.getLong("request_timeout_ms", 10_000),
                heartbeatTicks = fc.getLong("heartbeat_ticks", 600),
                leaderboardTicks = fc.getLong("leaderboard_ticks", 6000),
                webUrl = fc.getString("web_url", "")!!.ifBlank { base },
                docsDir = fc.getString("docs_dir", "docs")!!,
                welcomeBanner = fc.getBoolean("welcome_banner", true),
            )
        }
    }

    fun srvUrl(action: String): String = "$baseUrl/api/srv/$action?token=$token"
}
