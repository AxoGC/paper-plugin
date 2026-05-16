package net.axogc.paper.transport

import com.google.gson.JsonElement

/**
 * Platform response envelope (plan §8.2).
 *
 * Success:  {"code":"OK", "data": ... | null}
 * Failure:  {"code":"USER_NOT_FOUND", "data": null}
 *
 * NB: GET /api/srv/poll returns the *event* directly, NOT wrapped — see core handler.
 */
data class Envelope(val code: String, val data: JsonElement?) {
    val isOk: Boolean get() = code == "OK"
}

/** Thrown when the platform returns a non-OK envelope. */
class ApiException(val status: Int, val code: String) :
    RuntimeException("HTTP $status / $code")
