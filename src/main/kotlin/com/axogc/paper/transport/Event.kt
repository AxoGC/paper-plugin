package com.axogc.paper.transport

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Downstream event received from GET /api/srv/poll (plan §4.3).
 *   { "id": "evt_abc", "command": "player.kick", "data": { ... } }
 */
data class IncomingEvent(
    val id: String,
    val command: String,
    val data: JsonObject,
)

/**
 * Body for POST /api/srv/reply (plan §4.3).
 * Either [data] is set (success) or [errorCode] is set (failure).
 */
data class ReplyBody(
    val id: String,
    val ok: Boolean,
    val data: JsonElement? = null,
    val errorCode: String? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("ok", ok)
        if (data != null) add("data", data)
        if (errorCode != null) {
            add("error", JsonObject().apply { addProperty("code", errorCode) })
        }
    }

    companion object {
        fun ok(id: String, data: JsonElement? = null) = ReplyBody(id, true, data, null)
        fun fail(id: String, code: String) = ReplyBody(id, false, null, code)
    }
}
