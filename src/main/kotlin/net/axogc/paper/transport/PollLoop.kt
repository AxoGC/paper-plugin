package net.axogc.paper.transport

import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single dedicated thread that calls [ApiClient.poll] in a tight loop and feeds events
 * to [onEvent]. Plan §4.1: "/poll 拿到事件后无需等处理完即可再 poll".
 *
 * Backoff: on null result we re-poll immediately (the server's own 28s hang IS the backoff).
 * On *errors* the client logged it and returned null, so to avoid a hot loop on auth
 * failure or downed core we sleep [errorBackoffMs] between two consecutive nulls when the
 * first poll returned in well under [errorBackoffMs] / 2 ms (heuristic — a real long-poll
 * timeout takes ~28s and won't trigger backoff).
 */
class PollLoop(
    private val api: ApiClient,
    private val log: Logger,
    private val errorBackoffMs: Long = 5_000L,
    private val onEvent: (IncomingEvent) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread(::runLoop, "paperplatform-poll")
        t.isDaemon = true
        worker = t
        t.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        log.info("[platform] poll loop started")
        while (running.get()) {
            val started = System.currentTimeMillis()
            val evt: IncomingEvent? = try {
                api.poll()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                log.warning("[platform] poll loop unexpected: ${e.message}")
                null
            }
            if (evt != null) {
                try {
                    onEvent(evt)
                } catch (t: Throwable) {
                    log.warning("[platform] event handler threw for ${evt.command}: ${t.message}")
                }
                continue
            }
            // Null = timeout OR error. Apply backoff only when the call returned suspiciously fast.
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < errorBackoffMs / 2 && running.get()) {
                try {
                    Thread.sleep(errorBackoffMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        log.info("[platform] poll loop stopped")
    }
}
