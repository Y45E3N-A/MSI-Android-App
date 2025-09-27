package com.example.msiandroidapp.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

object PiSocketManager {
    private const val TAG = "PiSocketManager"

    // ---- Config / URL ----
    private var baseUrl: String = "http://192.168.4.1:5000"
    fun setBaseUrl(ipOrUrl: String) {
        baseUrl = if (ipOrUrl.startsWith("http")) ipOrUrl else "http://$ipOrUrl:5000"
    }

    fun reconnect() {
        try { disconnect() } catch (_: Exception) {}
        connect(previewImageCallback ?: { _, _ -> }, stateUpdateCallback ?: { })
    }

    // ---- Core ----
    private var socket: Socket? = null
    private val connecting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Emit buffer for events fired before connection is up
    private data class Queued(val event: String, val data: JSONObject?)
    private val emitQueue = ArrayDeque<Queued>()
    private const val MAX_QUEUED = 50

    // ---- High-throughput callbacks ----
    private var previewImageCallback: ((JSONObject, Bitmap) -> Unit)? = null
    private var stateUpdateCallback: ((JSONObject) -> Unit)? = null

    // ---- PMFI callbacks ----
    private var pmfiStatusCallback: ((JSONObject) -> Unit)? = null
    private var pmfiErrorCallback: ((String) -> Unit)? = null
    private var pmfiCompleteCallback: (() -> Unit)? = null

    // ---- Custom event bus (persists across reconnects) ----
    private val customHandlers: MutableMap<String, MutableList<(Any) -> Unit>> = ConcurrentHashMap()

    // ---- Connection state callback (optional) ----
    private var connectionStateCallback: ((connected: Boolean) -> Unit)? = null
    fun setConnectionStateListener(cb: ((Boolean) -> Unit)?) { connectionStateCallback = cb }

    // =========================================================
    // Public API
    // =========================================================
    fun connect(
        onPreviewImage: (JSONObject, Bitmap) -> Unit,
        onStateUpdate: (JSONObject) -> Unit
    ) {
        // Save latest callbacks
        previewImageCallback = onPreviewImage
        stateUpdateCallback  = onStateUpdate

        if (socket?.connected() == true || connecting.get()) return
        connecting.set(true)

        val opts = IO.Options().apply {
            forceNew = false
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1000
            reconnectionDelayMax = 8000
            randomizationFactor = 0.5
            transports = arrayOf("websocket", "polling")
            path = "/socket.io/"
            upgrade = true
        }

        // Build a fresh socket, bind *to this instance*, then assign
        val s = IO.socket(baseUrl, opts).apply {
            off() // clean slate

            // ---- Lifecycle ----
            on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Socket connected")
                connecting.set(false)
                notifyConnected(true)
                flushEmitQueue(this)
            }
            on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Socket disconnected")
                notifyConnected(false)
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.w(TAG, "Socket connect error: ${args.joinToString()}")
            }

            // ---- Manager-level reconnect logs ----
            try {
                val mgr: Manager = io()
                mgr.off(Manager.EVENT_RECONNECT_ATTEMPT)
                mgr.off(Manager.EVENT_RECONNECT)
                mgr.off(Manager.EVENT_RECONNECT_ERROR)
                mgr.off(Manager.EVENT_RECONNECT_FAILED)

                mgr.on(Manager.EVENT_RECONNECT_ATTEMPT) {
                    Log.d(TAG, "Reconnect attempt…")
                    jitterSleep()
                }
                mgr.on(Manager.EVENT_RECONNECT) {
                    Log.d(TAG, "Reconnected")
                }
                mgr.on(Manager.EVENT_RECONNECT_ERROR) { a ->
                    Log.w(TAG, "Reconnect error: ${a.joinToString()}")
                }
                mgr.on(Manager.EVENT_RECONNECT_FAILED) {
                    Log.e(TAG, "Reconnect failed")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Manager events unavailable: ${t.message}")
            }

            // ---- High-throughput preview/state ----
            bindAllPreviewEvents(this)
            bindStateHandler(this)

            // ---- PMFI routed events (matches server) ----
            bindEvent(this, "pmfi.plan") { payload ->
                (payload as? JSONObject)?.let { j ->
                    postToMain { customHandlers["pmfi.plan"]?.forEach { it(j) } }
                }
            }
            bindEvent(this, "pmfi.stage") { payload ->
                (payload as? JSONObject)?.let { j ->
                    postToMain { customHandlers["pmfi.stage"]?.forEach { it(j) } }
                }
            }
            bindEvent(this, "pmfi.progress") { payload ->
                (payload as? JSONObject)?.let { j ->
                    postToMain { customHandlers["pmfi.progress"]?.forEach { it(j) } }
                }
            }
            bindEvent(this, "pmfi.log") { payload ->
                (payload as? JSONObject)?.let { j ->
                    postToMain { customHandlers["pmfi.log"]?.forEach { it(j) } }
                }
            }
            bindEvent(this, "pmfi.sectionUploaded") { payload ->
                (payload as? JSONObject)?.let { j ->
                    postToMain { customHandlers["pmfi.sectionUploaded"]?.forEach { it(j) } }
                }
            }
// Note: server emits "pmfi.complete" (dot), not "pmfi_complete"
            bindEvent(this, "pmfi.complete") { payload ->
                (payload as? JSONObject)?.let { j ->
                    postToMain { customHandlers["pmfi.complete"]?.forEach { it(j) } }
                }
            }


            // ---- Rebind any previously registered custom handlers ----
            rebindCustomHandlers(this)
        }

        socket = s
        socket?.connect()
    }

    fun disconnect() {
        try {
            socket?.let {
                Log.d(TAG, "Disconnecting socket…")
                it.off()
                it.disconnect()
                it.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect", e)
        } finally {
            socket = null
            connecting.set(false)
            notifyConnected(false)
        }
    }

    /** Register a custom event handler (persists across reconnects). */
    fun on(event: String, handler: (Any) -> Unit) {
        val list = customHandlers.getOrPut(event) { CopyOnWriteArrayList() }
        list.add(handler)
        socket?.let { bindSingle(it, event, list) } // bind immediately if connected
    }

    /** Remove all handlers for an event. */
    fun off(event: String) {
        customHandlers.remove(event)
        socket?.off(event)
    }

    /** Emit a custom event; if not connected yet, queue briefly. */
    fun emit(event: String, data: JSONObject? = null) {
        val s = socket
        if (s?.connected() == true) {
            s.emit(event, data)
        } else {
            if (emitQueue.size >= MAX_QUEUED) emitQueue.removeFirst()
            emitQueue.addLast(Queued(event, data))
            Log.d(TAG, "Queued emit: $event (buffer=${emitQueue.size})")
        }
    }

    /** PMFI callbacks (set anytime). */
    fun setPmfiCallbacks(
        onStatus: (JSONObject) -> Unit,
        onError: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        pmfiStatusCallback  = onStatus
        pmfiErrorCallback   = onError
        pmfiCompleteCallback = onComplete
    }

    // =========================================================
    // Internals
    // =========================================================

    private fun notifyConnected(connected: Boolean) {
        postToMain { connectionStateCallback?.invoke(connected) }
    }

    private fun flushEmitQueue(s: Socket) {
        while (s.connected() && emitQueue.isNotEmpty()) {
            val item = emitQueue.removeFirst()
            s.emit(item.event, item.data)
            Log.d(TAG, "Flushed emit: ${item.event}")
        }
    }

    private fun rebindCustomHandlers(s: Socket) {
        for ((event, handlers) in customHandlers) {
            bindSingle(s, event, handlers)
        }
    }

    // --- Binding helpers (ALWAYS use the passed socket 's') ---

    private fun bindSingle(s: Socket, event: String, handlers: List<(Any) -> Unit>) {
        s.off(event)
        s.on(event) { args ->
            val payload: Any? = args.firstOrNull()?.let { parsePayload(it) }
            if (payload != null) {
                handlers.forEach { h ->
                    try { h(payload) } catch (e: Exception) {
                        Log.e(TAG, "Handler for '$event' threw", e)
                    }
                }
            }
        }
    }

    private fun bindEvent(s: Socket, event: String, dispatcher: (Any?) -> Unit) {
        s.off(event)
        s.on(event) { args ->
            try {
                val payload = args.firstOrNull()?.let { parsePayload(it) }
                dispatcher(payload)
                // Also forward to any user-registered handlers for the same event
                customHandlers[event]?.forEach { h ->
                    try { h(payload ?: JSONObject()) } catch (e: Exception) {
                        Log.e(TAG, "Custom handler for '$event' threw", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dispatcher error for '$event'", e)
            }
        }
    }

    private fun bindAllPreviewEvents(s: Socket) {
        arrayOf("preview_image", "preview", "preview_jpeg", "preview_frame").forEach { ev ->
            s.off(ev)
            s.on(ev) { args ->
                try {
                    val data = parsePayload(args.firstOrNull())
                    val (json, bmp) = extractBitmapFromPayload(data) ?: return@on
                    postToMain { previewImageCallback?.invoke(json, bmp) }
                } catch (e: Exception) {
                    Log.e(TAG, "preview handler error", e)
                }
            }
        }
    }

    private fun bindStateHandler(s: Socket) {
        s.off("state_update")
        s.on("state_update") { args ->
            try {
                val data = args.firstOrNull()?.let { parsePayload(it) as? JSONObject } ?: return@on
                postToMain { stateUpdateCallback?.invoke(data) }
            } catch (e: Exception) {
                Log.e(TAG, "state_update handler error", e)
            }
        }
    }

    // --- Payload helpers ---

    private fun extractBitmapFromPayload(payload: Any?): Pair<JSONObject, Bitmap>? {
        return when (payload) {
            is JSONObject -> {
                val b64 = when {
                    payload.has("image_b64") -> payload.optString("image_b64", "")
                    payload.has("image")     -> payload.optString("image", "")
                    else -> ""
                }
                if (b64.isBlank()) return null
                val bytes = decodeBase64(b64) ?: return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                payload to bmp
            }
            is String -> {
                val bytes = decodeBase64(payload) ?: return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                JSONObject() to bmp
            }
            else -> null
        }
    }

    private fun decodeBase64(s: String): ByteArray? {
        return try {
            Base64.decode(s, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            try { Base64.decode(s, Base64.URL_SAFE) } catch (_: IllegalArgumentException) { null }
        }
    }

    private fun parsePayload(arg: Any?): Any? = when (arg) {
        is JSONObject -> arg
        is String -> runCatching { JSONObject(arg) }.getOrElse { arg }
        else -> arg
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun jitterSleep() {
        // tiny random pause 0–150ms to avoid stampede when multiple screens re-bind
        try { Thread.sleep(Random.nextLong(0, 150)) } catch (_: InterruptedException) { }
    }
}
