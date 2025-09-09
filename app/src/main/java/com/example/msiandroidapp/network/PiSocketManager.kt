package com.example.msiandroidapp.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import org.json.JSONObject

object PiSocketManager {
    private const val TAG = "PiSocketManager"

    private var mSocket: Socket? = null

    // Dedicated callbacks for high-throughput preview/state
    private var previewImageCallback: ((JSONObject, Bitmap) -> Unit)? = null
    private var stateUpdateCallback: ((JSONObject) -> Unit)? = null

    // Generic event bus: event -> list of handlers
    private val customEventHandlers: MutableMap<String, MutableList<(Any) -> Unit>> = mutableMapOf()

    fun connect(
        ip: String,
        onPreviewImage: (JSONObject, Bitmap) -> Unit,
        onStateUpdate: (JSONObject) -> Unit
    ) {
        // Save latest callbacks
        previewImageCallback = onPreviewImage
        stateUpdateCallback = onStateUpdate

        // Fresh start
        disconnect()

        val opts = IO.Options().apply {
            forceNew = true
            reconnection = true
        }
        mSocket = IO.socket("http://$ip:5000", opts)

        // ----- Built-in: preview_image -----
        mSocket?.on("preview_image") { args ->
            try {
                if (args.isNotEmpty()) {
                    val data = parseToJSONObject(args[0]) ?: return@on
                    val imgB64 = data.optString("image_b64", "")
                    if (imgB64.isNotEmpty()) {
                        val imgBytes = Base64.decode(imgB64, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                        if (bmp != null) {
                            previewImageCallback?.invoke(data, bmp)
                        } else {
                            Log.w(TAG, "Bitmap decode failed")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "preview_image handler error", e)
            }
        }

        // ----- Built-in: state_update -----
        mSocket?.on("state_update") { args ->
            try {
                if (args.isNotEmpty()) {
                    val data = parseToJSONObject(args[0])
                    if (data != null) stateUpdateCallback?.invoke(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "state_update handler error", e)
            }
        }

        // ----- Connection lifecycle logs -----
        mSocket?.on(Socket.EVENT_CONNECT) { Log.d(TAG, "Socket connected") }
        mSocket?.on(Socket.EVENT_DISCONNECT) { Log.d(TAG, "Socket disconnected") }
        mSocket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.w(TAG, "Socket connect error: ${args.joinToString()}")
        }

        // ---- Reconnect events via Manager (portable across versions) ----
        try {
            val manager: Manager? = mSocket?.io()
            manager?.off(Manager.EVENT_RECONNECT_ATTEMPT)
            manager?.off(Manager.EVENT_RECONNECT)
            manager?.off(Manager.EVENT_RECONNECT_ERROR)
            manager?.off(Manager.EVENT_RECONNECT_FAILED)

            manager?.on(Manager.EVENT_RECONNECT_ATTEMPT) {
                Log.d(TAG, "Socket reconnect attempt")
            }
            manager?.on(Manager.EVENT_RECONNECT) {
                Log.d(TAG, "Socket reconnected")
            }
            manager?.on(Manager.EVENT_RECONNECT_ERROR) { args ->
                Log.w(TAG, "Socket reconnect error: ${args.joinToString()}")
            }
            manager?.on(Manager.EVENT_RECONNECT_FAILED) {
                Log.e(TAG, "Socket reconnect failed")
            }
        } catch (t: Throwable) {
            // Safe ignore: on very old libs Manager may differ; we simply won't log reconnects.
            Log.w(TAG, "Manager reconnect events not available: ${t.message}")
        }

        // Bind any previously-registered custom handlers (e.g., cal_* events)
        rebindCustomHandlers()

        mSocket?.connect()
    }

    /** Register a custom event handler (persisted across reconnects). */
    fun on(event: String, handler: (Any) -> Unit) {
        val list = customEventHandlers.getOrPut(event) { mutableListOf() }
        list.add(handler)
        bindSingleEvent(event)
    }

    /** Remove all handlers for an event. */
    fun off(event: String) {
        customEventHandlers.remove(event)
        mSocket?.off(event)
    }

    /** Emit a custom event to the Pi. */
    fun emit(event: String, data: JSONObject? = null) {
        Log.d(TAG, "Emitting event: $event, data: $data")
        mSocket?.emit(event, data)
    }

    /** Cleanly disconnect and clear listeners (keeps customEventHandlers for future reconnect). */
    fun disconnect() {
        try {
            mSocket?.let { sock ->
                Log.d(TAG, "Disconnecting socket...")
                sock.off()
                sock.disconnect()
                sock.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect", e)
        } finally {
            mSocket = null
        }
    }

    // ----- Internals -----

    private fun parseToJSONObject(arg: Any?): JSONObject? =
        when (arg) {
            is JSONObject -> arg
            is String -> try { JSONObject(arg) } catch (_: Exception) { null }
            else -> null
        }

    private fun rebindCustomHandlers() {
        val socket = mSocket ?: return
        for (event in customEventHandlers.keys) {
            bindSingleEvent(event)
        }
    }

    private fun bindSingleEvent(event: String) {
        val socket = mSocket ?: return
        val handlers = customEventHandlers[event] ?: return
        socket.off(event)
        socket.on(event) { args ->
            try {
                val payload: Any? = if (args.isNotEmpty()) {
                    parseToJSONObject(args[0]) ?: args[0]
                } else null
                if (payload != null) {
                    handlers.forEach { h ->
                        try { h(payload) } catch (e: Exception) {
                            Log.e(TAG, "Handler for '$event' threw", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in dispatcher for '$event'", e)
            }
        }
    }
}
