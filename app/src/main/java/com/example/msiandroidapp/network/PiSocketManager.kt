package com.example.msiandroidapp.network

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

object PiSocketManager {
    private var mSocket: Socket? = null
    private var previewImageCallback: ((JSONObject, android.graphics.Bitmap) -> Unit)? = null
    private var stateUpdateCallback: ((JSONObject) -> Unit)? = null

    fun connect(
        ip: String,
        onPreviewImage: (JSONObject, android.graphics.Bitmap) -> Unit,
        onStateUpdate: (JSONObject) -> Unit
    ) {
        // Always update callbacks!
        previewImageCallback = onPreviewImage
        stateUpdateCallback = onStateUpdate

        // Always disconnect first to ensure fresh state
        disconnect()

        val opts = IO.Options().apply {
            forceNew = true
            reconnection = true
        }
        mSocket = IO.socket("http://$ip:5000", opts)

        mSocket?.on("preview_image") { args ->
            if (args.isNotEmpty()) {
                val data = parseToJSONObject(args[0])
                val imgB64 = data?.optString("image_b64") ?: return@on
                val imgBytes = Base64.decode(imgB64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                if (data != null && bitmap != null) {
                    Log.d("PiSocketManager", "Decoded bitmap, invoking callback. Bitmap: $bitmap")
                    previewImageCallback?.invoke(data, bitmap)
                }
            }
        }
        mSocket?.on("state_update") { args ->
            if (args.isNotEmpty()) {
                val data = parseToJSONObject(args[0])
                if (data != null) {
                    Log.d("PiSocketManager", "Received state_update: $data")
                    stateUpdateCallback?.invoke(data)
                }
            }
        }
        mSocket?.on(Socket.EVENT_CONNECT) {
            Log.d("PiSocketManager", "Socket connected!")
        }
        mSocket?.on(Socket.EVENT_DISCONNECT) {
            Log.d("PiSocketManager", "Socket disconnected!")
        }
        mSocket?.connect()
    }

    fun emit(event: String, data: JSONObject? = null) {
        Log.d("PiSocketManager", "Emitting event: $event, data: $data")
        mSocket?.emit(event, data)
    }

    fun disconnect() {
        if (mSocket != null) {
            Log.d("PiSocketManager", "Disconnecting socket...")
            mSocket?.disconnect()
            mSocket?.off()
            mSocket = null
        }
    }

    private fun parseToJSONObject(arg: Any?): JSONObject? {
        return when (arg) {
            is JSONObject -> arg
            is String -> try { JSONObject(arg) } catch (_: Exception) { null }
            else -> null
        }
    }
}
