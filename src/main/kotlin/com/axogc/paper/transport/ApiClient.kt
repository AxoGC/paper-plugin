package com.axogc.paper.transport

import com.axogc.paper.config.PluginConfig
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

/**
 * Thin wrapper around the platform HTTP API.
 *
 * Protocol invariants (plan §4):
 *  - All endpoints take ?token=<token> in the query string.
 *  - All responses are an Envelope {code, data} EXCEPT GET /api/srv/poll,
 *    which returns the raw event {id, command, data} or 204 No Content.
 *  - Bodies are JSON. Single message ≤1MB.
 *  - Keep-alive must be enabled (HttpClient does this by default).
 */
class ApiClient(
    private val cfg: PluginConfig,
    private val log: Logger,
) {
    private val gson = Gson()

    private val pollClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs))
        .version(HttpClient.Version.HTTP_1_1) // long-poll friendlier than h2 multiplexed
        .build()

    private val unaryClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(cfg.connectTimeoutMs))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /**
     * Long-poll for the next event. Returns null on 204 (timeout / no event) or on retryable error.
     * Caller is expected to loop immediately.
     */
    fun poll(): IncomingEvent? {
        val req = HttpRequest.newBuilder(URI.create(cfg.srvUrl("poll")))
            .timeout(Duration.ofMillis(cfg.pollTimeoutMs))
            .GET()
            .build()
        return try {
            val resp = pollClient.send(req, HttpResponse.BodyHandlers.ofString())
            when (resp.statusCode()) {
                204 -> null
                200 -> parseEvent(resp.body())
                401, 403 -> {
                    log.warning("[platform] poll auth failure: HTTP ${resp.statusCode()} — check token")
                    null
                }
                else -> {
                    log.warning("[platform] poll HTTP ${resp.statusCode()}: ${truncate(resp.body())}")
                    null
                }
            }
        } catch (e: java.net.http.HttpTimeoutException) {
            null // expected when the server keeps the connection open and times out
        } catch (e: Exception) {
            log.warning("[platform] poll error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** POST /api/srv/reply (fire-and-forget, but logs failures). */
    fun reply(body: ReplyBody) {
        post("reply", body.toJson())
    }

    /** Generic upstream POST. Returns the envelope's `data` element if OK, else null. */
    fun post(action: String, body: JsonElement): JsonElement? {
        val req = HttpRequest.newBuilder(URI.create(cfg.srvUrl(action)))
            .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        return try {
            val resp = unaryClient.send(req, HttpResponse.BodyHandlers.ofString())
            handleEnvelope(action, resp)
        } catch (e: Exception) {
            log.warning("[platform] POST $action failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Generic upstream GET. */
    fun get(action: String): JsonElement? {
        val req = HttpRequest.newBuilder(URI.create(cfg.srvUrl(action)))
            .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
            .GET()
            .build()
        return try {
            val resp = unaryClient.send(req, HttpResponse.BodyHandlers.ofString())
            handleEnvelope(action, resp)
        } catch (e: Exception) {
            log.warning("[platform] GET $action failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun handleEnvelope(action: String, resp: HttpResponse<String>): JsonElement? {
        val body = resp.body() ?: return null
        if (body.isBlank()) return null
        val env = try {
            val obj = JsonParser.parseString(body).asJsonObject
            Envelope(
                code = obj.get("code")?.asString ?: "",
                data = obj.get("data"),
            )
        } catch (e: Exception) {
            log.warning("[platform] $action: invalid envelope (HTTP ${resp.statusCode()}): ${truncate(body)}")
            return null
        }
        if (!env.isOk) {
            log.fine("[platform] $action returned ${env.code}")
            return null
        }
        return env.data
    }

    private fun parseEvent(raw: String): IncomingEvent? {
        if (raw.isBlank()) return null
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            val id = obj.get("id")?.asString ?: return null
            val command = obj.get("command")?.asString ?: return null
            val data = obj.get("data")?.let { if (it.isJsonObject) it.asJsonObject else JsonObject() }
                ?: JsonObject()
            IncomingEvent(id, command, data)
        } catch (e: Exception) {
            log.warning("[platform] failed to parse event: ${truncate(raw)}")
            null
        }
    }

    private fun truncate(s: String?, max: Int = 256): String =
        if (s == null) "<null>" else if (s.length <= max) s else s.substring(0, max) + "..."
}
