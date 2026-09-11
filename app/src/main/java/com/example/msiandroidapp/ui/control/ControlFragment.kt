package com.example.msiandroidapp.ui.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.msiandroidapp.R
import com.example.msiandroidapp.databinding.FragmentControlBinding
import com.example.msiandroidapp.network.PiApi
import com.example.msiandroidapp.network.ModeHandshake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import com.example.msiandroidapp.network.PiSocketManager
import com.example.msiandroidapp.network.PmfiStartBody
import com.example.msiandroidapp.network.AmsiStartBody
import com.example.msiandroidapp.network.FanControlBody
import com.example.msiandroidapp.util.UploadProgressBus
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.example.msiandroidapp.MainActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import com.example.msiandroidapp.ui.pmfi.PmfiEditorActivity
import android.os.Build
import android.widget.ProgressBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

private const val TAG = "ControlFragment"

// simple toast helper
private fun Fragment.toast(msg: String) =
    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

@SuppressLint("MissingPermission")
private suspend fun lastKnownLocationStr(ctx: Context): String {
    return try {
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val loc: Location? = fused.lastLocation.getResult()
        if (loc != null) "Lat: %.5f, Lon: %.5f".format(loc.latitude, loc.longitude) else "Unknown"
    } catch (_: Exception) {
        "Unknown"
    }
}

class ControlFragment : Fragment() {

    private val socketSubscriptions = mutableListOf<Pair<String, (Any) -> Unit>>()

    private fun onSocketEvent(event: String, handler: (Any) -> Unit) {
        val ownerBinding = _binding ?: return
        val guarded: (Any) -> Unit = { payload ->
            runOnViewThread {
                if (_binding === ownerBinding) handler(payload)
            }
        }
        socketSubscriptions += event to guarded
        PiSocketManager.on(event, guarded)
    }

    private fun runOnViewThread(block: () -> Unit) {
        val ownerBinding = _binding ?: return
        activity?.runOnUiThread {
            if (isAdded && _binding === ownerBinding) block()
        }
    }

    // ===== View binding (lifecycle-safe) =====
    private var _binding: FragmentControlBinding? = null
    private val binding: FragmentControlBinding
        get() = _binding ?: error("Binding is only valid between onCreateView and onDestroyView")

    // ViewModel
    private val vm: ControlViewModel by activityViewModels()
    // Cumulative total_done rollover guards (class-level so both handlers share them)
    private var lastSectionIndexForTot = -1
    private var lastSecDoneForTot = 0

    // Connection / state
    private var currentIp = ""
    private var isPiConnected = false
    // ---- PMFI cumulative tracking across ZIPs ----
    private var pmfiCumOffset = 0      // how many frames we've already counted before current zip
    private var pmfiLastTot   = 0      // last raw total_done we've seen (from server)

    private var isConnecting = false
    private var isCaptureOngoing = false
    private var isPmfiRunning = false
    private var isCalibratingOngoing = false
    private var lastSw4FromServer: Boolean = false
    // Auto-resume preview (LED warming) after jobs finish
    private var resumePreviewPending = false
    private var resumeJob: Job? = null
    private var calStartGraceUntil = 0L
    private var calDarkFrameSeen = false
    private var calExtraImagesExpected = 0
    private var calDarkImagesUploaded = 0
    private var calUploadedImages = 0
    private var calUploadTotalImages = 16
    private var calInfoLine: String = ""
    private var calStageLine: String = ""
    private var calExpectedImages = 16
    private var calTotalChannels = 16

    // Track preview state mirrored from server
    private var modeHandshakeVersion = 0
    private var modeTransitionInProgress = false
    private var serverTransitionInProgress = false
    private val modeTransitionMutex = Mutex()
    private var previewActive = false
    private var previewRequestedState: Boolean? = null
    private var previewRequestPendingUntil: Long = 0L
    private val previewRequestAckWindowMs = 900L
    // Remember preview state before AMSI so we can restore warming afterwards
    private var wasPreviewOnBeforeAmsi = false
    private var amsiSocketPreviewEnabled = false
    private var amsiSocketPreviewToggleInFlight = false
    private var deviceChannelCount = 16
    private var channelWavelengths: List<Int?> = List(16) { null }
    private var selectedAmsiChannels: Set<Int> = (0 until 16).toSet()
    private var capabilitiesReady = false
    private var fanControlAvailable = false
    private var fanRequestInFlight = false

    // Preview UI
    private enum class PreviewMode {
        OFF,
        STARTING,
        LIVE_FEED,
        AMSI_GRID
    }

    private var mode: PreviewMode = PreviewMode.OFF

    private lateinit var previewContainer: FrameLayout
    private var liveImage: ImageView? = null
    private val gridImages = ArrayList<ImageView>(16)

    // Polling / watchdog
    private var pollJob: Job? = null
    private var disconnectJob: Job? = null
    private var lastOkTimestamp = 0L
    private var consecutiveStatusFailures = 0
    // --- ENV telemetry (latest + freshness tracking) ---
    private var latestTempC: Double? = null
    private var latestHumidity: Double? = null
    private var latestEnvIso: String? = null
    private var lastEnvEventAt: Long = 0L
    private var latestCpuTempC: Double? = null
    private var latestCpuTempIso: String? = null
    private var lastCpuTempEventAt: Long = 0L
    private var showingCpuTemp: Boolean = false
    private var lastThermalThrottleToastAt: Long = 0L

    // Poll fallback every 20s if we haven't seen a socket event recently
    private val envPollMs = 20_000L
    private val cpuTempPollMs = 20_000L

    private val pollMs = 5_000L
    private val disconnectGraceMs = 3_000L

    // fast /status client
    private val quickClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    // ===== Fragment lifecycle =====
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControlBinding.inflate(inflater, container, false)
        return binding.root
    }
    // If we haven't seen an env event in a while, attempt a light HTTP poll

    private fun showPreviewOffState() {
        binding.previewFrame.visibility = View.GONE
        mode = PreviewMode.OFF
        previewActive = false

        liveImage = null
        previewContainer.removeAllViews()

        binding.previewTitleText.text = "Camera Preview"
        binding.previewSubtitleText.text = "Use preview to align the leaf before capture."
        binding.previewStatusChip.text = "Off"

        binding.previewEmptyState.visibility = View.VISIBLE
        binding.previewLoadingState.visibility = View.GONE
        binding.previewOverlay.visibility = View.GONE
    }

    private fun showPreviewStartingState() {
        binding.previewFrame.visibility = View.VISIBLE
        mode = PreviewMode.STARTING

        liveImage = null
        previewContainer.removeAllViews()

        binding.previewTitleText.text = "Camera Preview"
        binding.previewSubtitleText.text = "Connecting to camera stream..."
        binding.previewStatusChip.text = "Starting"

        binding.previewEmptyState.visibility = View.GONE
        binding.previewLoadingState.visibility = View.VISIBLE
        binding.previewOverlay.visibility = View.GONE
    }

    private fun startLivePreview() {
        if (mode == PreviewMode.LIVE_FEED && liveImage != null) return

        binding.previewFrame.visibility = View.VISIBLE
        mode = PreviewMode.LIVE_FEED
        previewActive = true

        binding.previewTitleText.text = "Live Preview"
        binding.previewSubtitleText.text = "Real-time camera alignment view."
        binding.previewStatusChip.text = "Live"

        binding.previewEmptyState.visibility = View.GONE
        binding.previewLoadingState.visibility = View.GONE
        binding.previewOverlay.visibility = View.VISIBLE
        binding.previewOverlayText.text = "Live Preview"
        binding.previewFpsText.text = "Live"

        liveImage = ImageView(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        previewContainer.removeAllViews()
        previewContainer.addView(liveImage)
    }



    private fun clearPreview() {
        showPreviewOffState()
        hideAmsiGrid()
    }

    private fun startImageGrid() {
        showPreviewOffState()

        mode = PreviewMode.AMSI_GRID
        showAmsiGrid()

        binding.amsiGridStatusChip.text = "0/$amsiCaptureTotal"
        binding.amsiGridProgressBar.max = amsiCaptureTotal
        binding.amsiGridProgressBar.progress = 0

        gridImages.forEach { imageView ->
            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            imageView.alpha = 0.45f
        }
    }

    private fun showAmsiGrid() {
        binding.amsiGridCard.visibility = View.VISIBLE
    }

    private fun hideAmsiGrid() {
        binding.amsiGridCard.visibility = View.GONE
        binding.amsiGridProgressBar.progress = 0
        binding.amsiGridStatusChip.text = "0/$amsiCaptureTotal"

        gridImages.forEach { imageView ->
            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            imageView.alpha = 0.45f
        }
    }

    private fun updateGrid(bitmaps: List<Bitmap?>) {
        if (gridImages.isEmpty()) return

        val received = bitmaps.count { it != null }.coerceIn(0, amsiCaptureTotal)

        showAmsiGrid()

        binding.amsiGridProgressBar.max = amsiCaptureTotal
        binding.amsiGridProgressBar.progress = received
        binding.amsiGridStatusChip.text = "$received/$amsiCaptureTotal"

        for (i in gridImages.indices) {
            val imageView = gridImages.getOrNull(i) ?: continue
            val bitmap = bitmaps.getOrNull(i)

            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                imageView.alpha = 1f
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                imageView.alpha = 0.45f
            }
        }
    }

    private fun amsiPreviewIndex(data: JSONObject): Int {
        var idx = data.optInt("index", -1)
        if (idx !in 0 until deviceChannelCount) idx = data.optInt("idx", -1)
        if (idx !in 0 until deviceChannelCount) idx = data.optInt("i", -1)
        if (idx !in 0 until deviceChannelCount) idx = data.optInt("channel", -1)
        if (idx !in 0 until deviceChannelCount) idx = data.optInt("led", -1)
        return idx
    }

    private fun addAmsiGridBitmap(data: JSONObject, bmp: Bitmap) {
        var idx = amsiPreviewIndex(data)

        if (idx !in 0 until deviceChannelCount) {
            val current = vm.capturedBitmaps.value ?: List(deviceChannelCount) { null }
            idx = current.indexOfFirst { it == null }.takeIf { it >= 0 } ?: -1
        }

        if (idx in 0 until deviceChannelCount) {
            if (mode != PreviewMode.AMSI_GRID) {
                mode = PreviewMode.AMSI_GRID
                showAmsiGrid()
            }
            vm.addBitmap(idx, bmp)
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.controlsScrollview.setConnectionCard(binding.connectionCard)
        binding.topConnectionChip.contentDescription = "Connection status; tap to show connection controls"
        binding.topConnectionChip.setOnClickListener {
            binding.controlsScrollview.revealConnection()
        }

        // ----- Cache a few views for preview area -----
        previewContainer = binding.previewContainer

        gridImages.clear()
        gridImages.addAll(
            listOf(
                binding.amsiTileImage01,
                binding.amsiTileImage02,
                binding.amsiTileImage03,
                binding.amsiTileImage04,
                binding.amsiTileImage05,
                binding.amsiTileImage06,
                binding.amsiTileImage07,
                binding.amsiTileImage08,
                binding.amsiTileImage09,
                binding.amsiTileImage10,
                binding.amsiTileImage11,
                binding.amsiTileImage12,
                binding.amsiTileImage13,
                binding.amsiTileImage14,
                binding.amsiTileImage15,
                binding.amsiTileImage16
            )
        )

        showPreviewOffState()
        hideAmsiGrid()

        // ===== Initial UI wiring / click handlers =====
        setupButtons()
        observeUploadProgress()
        hookSystemThrottleSocket()
        hookCpuTempSocket()

        // ===== Socket listeners (core connection + env + battery + cal + amsi + pmfi) =====
        hookSocketCore()      // "connect", "disconnect", "error" → updateConnUi(...)
        hookEnvSocket()       // "env.update" → renderEnv(...)
        hookBatterySocket()   // "battery.update" → renderBatteryFromJson(...)
        onSocketEvent("device.capabilities") { payload ->
            val capabilities = payload as? JSONObject ?: return@onSocketEvent
            if (!isAdded) return@onSocketEvent
            runOnViewThread { applyDeviceCapabilities(capabilities) }
        }
        onSocketEvent("fan.state") { payload ->
            val state = payload as? JSONObject ?: return@onSocketEvent
            if (!isAdded) return@onSocketEvent
            runOnViewThread { renderFanState(state.optBoolean("force_on", false)) }
        }

        // --- AMSI progress/status from Pi. This stays useful even when Socket.IO image previews are off. ---
        onSocketEvent("amsi.started") { payload ->
            val j = payload as? JSONObject
            val sessionId = j?.optString("session_id")?.takeIf { it.isNotBlank() }
            val channels = j?.optInt("channels", 16) ?: 16
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                val availableChannels = j?.optInt("available_channels", deviceChannelCount)
                    ?.coerceAtLeast(1) ?: deviceChannelCount
                if (availableChannels != deviceChannelCount || j?.has("wavelengths") == true) {
                    val wavelengths = j?.optJSONArray("wavelengths")
                    applyDeviceCapabilities(JSONObject().apply {
                        put("channel_count", availableChannels)
                        put("fan_control", fanControlAvailable)
                        put("channels", org.json.JSONArray().apply {
                            repeat(availableChannels) { index ->
                                put(JSONObject().apply {
                                    put("index", index)
                                    put("wavelength_nm", wavelengths?.optInt(index, 0) ?: 0)
                                })
                            }
                        })
                    })
                }
                currentAmsiRunId = sessionId
                currentAmsiStage = "capturing"
                amsiCaptureTotal = channels.coerceAtLeast(1)
                amsiZipUploadNotified = false
                isCaptureOngoing = true
                vm.isCapturing.value = true
                setUiBusy(true)
                if (amsiSocketPreviewEnabled) {
                    startImageGrid()
                    vm.prepareCapture(deviceChannelCount)
                } else {
                    clearPreview()
                }
                setAmsiCapturing(0, amsiCaptureTotal)
            }
        }

        onSocketEvent("amsi.progress") { payload ->
            val j = payload as? JSONObject ?: return@onSocketEvent
            val sessionId = j.optString("session_id").takeIf { it.isNotBlank() }
            val total = j.optInt("total", amsiCaptureTotal).coerceAtLeast(1)
            val serverPercent = j.optInt("percent_complete", -1).takeIf { it in 0..100 }
            val done = when {
                j.has("completed") -> j.optInt("completed", 0)
                j.has("percent_complete") -> {
                    val pct = j.optInt("percent_complete", 0).coerceIn(0, 100)
                    ((pct / 100.0) * total).toInt().coerceAtLeast(0)
                }
                else -> j.optInt("index", -1) + 1
            }.coerceIn(0, total)
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                if (sessionId != null && currentAmsiRunId != null && sessionId != currentAmsiRunId) {
                    return@runOnViewThread
                }
                if (currentAmsiRunId == null) currentAmsiRunId = sessionId
                if (currentAmsiStage.isNotBlank() && currentAmsiStage != "capturing") {
                    return@runOnViewThread
                }
                currentAmsiStage = "capturing"
                isCaptureOngoing = true
                vm.isCapturing.value = true
                val current = binding.captureProgressBar.progress
                val wavelengthNm = j.optJSONObject("capture")?.optInt("wavelength_nm", -1) ?: -1
                val statusText = if (wavelengthNm > 0) {
                    val shownDone = maxOf(current, done)
                    val shownPercent = if (shownDone == done) serverPercent else null
                    "Capturing $wavelengthNm nm: $shownDone/$total (${percentText(shownDone, total, shownPercent)})"
                } else {
                    null
                }
                setAmsiCapturing(maxOf(current, done), total, statusText)
            }
        }

        onSocketEvent("amsi.stage") { payload ->
            val j = payload as? JSONObject ?: return@onSocketEvent
            val sessionId = j.optString("session_id").takeIf { it.isNotBlank() }
            val stage = j.optString("stage", "").lowercase(Locale.UK)
            val total = j.optInt("total", amsiCaptureTotal).coerceAtLeast(1)
            val completed = j.optInt("completed", binding.captureProgressBar.progress).coerceIn(0, total)
            val serverPercent = j.optInt("percent_complete", -1).takeIf { it in 0..100 }
            val message = j.optString("message", "").takeIf { it.isNotBlank() }
            val wavelengthNm = j.optInt("wavelength_nm", -1)
            val fallback = when (stage) {
                "capturing" -> if (wavelengthNm > 0) {
                    "Capturing $wavelengthNm nm: $completed/$total (${percentText(completed, total, serverPercent)})"
                } else {
                    "Capturing on MFi: $completed/$total (${percentText(completed, total, serverPercent)})"
                }
                "converting" -> "Converting images to PNG: $completed/$total (${percentText(completed, total, serverPercent)})"
                "packing" -> "Packing AMSI ZIP"
                "uploading" -> "Uploading AMSI ZIP..."
                "upload_complete" -> "AMSI ZIP upload complete"
                "upload_failed" -> "AMSI ZIP upload failed"
                else -> "AMSI: $completed/$total"
            }
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                if (sessionId != null && currentAmsiRunId != null && sessionId != currentAmsiRunId) {
                    return@runOnViewThread
                }
                if (currentAmsiRunId == null) currentAmsiRunId = sessionId
                currentAmsiStage = stage
                isCaptureOngoing = stage !in setOf("upload_complete", "upload_failed")
                vm.isCapturing.value = isCaptureOngoing
                when (stage) {
                    "capturing" -> setAmsiCapturing(completed, total, message ?: fallback)
                    "converting" -> showAmsiProgress(completed, total, message ?: fallback)
                    "packing" -> showAmsiProgress(amsiCaptureTotal, amsiCaptureTotal, message ?: fallback)
                    "uploading" -> setAmsiUploadingZip(message ?: fallback)
                    "upload_complete" -> setAmsiUploadComplete(message ?: fallback)
                    "upload_failed" -> showAmsiProgress(0, 1, message ?: fallback)
                    else -> showAmsiProgress(completed, total, message ?: fallback)
                }
            }
        }

        onSocketEvent("amsi.uploaded") { _payload ->
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                if (!amsiZipUploadNotified) {
                    amsiZipUploadNotified = true
                    setAmsiUploadComplete()
                }
            }
        }

        onSocketEvent("amsi.complete") { _payload ->
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                setAmsiUploadComplete()
                isCaptureOngoing = false
                vm.isCapturing.value = false
                setUiBusy(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1500)
                    if (!isAdded) return@launch
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                    currentAmsiRunId = null
                    currentAmsiStage = ""
                    if (wasPreviewOnBeforeAmsi) kickPreviewResume()
                    wasPreviewOnBeforeAmsi = false
                }
            }
        }

        // --- AMSI abort/error from Pi ---
        onSocketEvent("amsi_error") { _payload ->
            if (!isAdded) return@onSocketEvent
            val errorMessage = (_payload as? JSONObject)?.optString("message")
            runOnViewThread {
                isCaptureOngoing = false
                vm.isCapturing.value = false
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE
                currentAmsiStage = ""

                clearPreview()
                setUiBusy(false)
                if (wasPreviewOnBeforeAmsi) kickPreviewResume()
                wasPreviewOnBeforeAmsi = false
                toast(errorMessage?.takeIf { it.isNotBlank() } ?: "Capture failed")
            }
        }


        // --- Calibration progress / complete / error from Pi ---
        // We'll inline what used to be hookCalibrationSocket(), but with the fixed cleanup-on-error
        onSocketEvent("cal_plan") { payload ->
            val j = payload as? JSONObject ?: return@onSocketEvent
            val channels = j.optInt("channels", deviceChannelCount)
            val darkExpected = j.optInt("dark_images_expected", 0)
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                calTotalChannels = channels
                vm.startCalibration(totalChannels = channels)
                calDarkFrameSeen = darkExpected > 0
                calExtraImagesExpected = darkExpected.coerceAtLeast(0)
                calExpectedImages = channels.coerceAtLeast(1)
                calDarkImagesUploaded = 0
                calUploadedImages = 0
                calUploadTotalImages = (channels + darkExpected.coerceAtLeast(0)).coerceAtLeast(channels.coerceAtLeast(1))
                calStageLine = ""
                calInfoLine = ""
                binding.calProgressBar.setDeterminateProgress(0, calExpectedImages)
                if (binding.calProgressBar.visibility != View.VISIBLE) {
                    binding.calProgressBar.visibility = View.VISIBLE
                }
                if (binding.calProgressText.visibility != View.VISIBLE) {
                    binding.calProgressText.visibility = View.VISIBLE
                }
                binding.calProgressText.text = "Calibration: 0/${binding.calProgressBar.max} (0%)"
            }
        }

        onSocketEvent("cal_stage") { payload ->
            val j = payload as? JSONObject ?: return@onSocketEvent
            val stage = j.optString("stage", "")
            val ch = j.optInt("channel_index", -1)
            val total = j.optInt("total_channels", deviceChannelCount).coerceAtLeast(1)
            val orderTotal = j.optInt("order_total", total).coerceAtLeast(1)
            val serverPercent = j.optInt("percent_complete", -1).takeIf { it in 0..100 }
            val orderedDone = when {
                j.has("completed") -> j.optInt("completed", 0)
                j.has("order_position") -> j.optInt("order_position", 0)
                else -> ch + 1
            }.coerceIn(0, orderTotal)
            val wl = j.optInt("wavelength_nm", -1)
            val stageNorm = if (j.has("led_norm")) j.optDouble("led_norm", Double.NaN) else Double.NaN
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                calTotalChannels = total
                binding.calProgressBar.max = orderTotal
                val stageKey = stage.lowercase(Locale.US)
                if (ch >= 0 && (stageKey == "capturing_dark" || stageKey == "calibrating")) {
                    binding.calProgressBar.setDeterminateProgress(orderedDone, orderTotal)
                }
                val wlText = if (wl > 0) " ${wl} nm" else ""
                calStageLine = when (stageKey) {
                    "capturing_dark" -> "Capturing dark$wlText"
                    "calibrating" -> "Calibrating$wlText"
                    "channel_complete" -> {
                        binding.calProgressBar.setDeterminateProgress(orderedDone, orderTotal)
                        "Calibrated$wlText"
                    }
                    "uploading" -> {
                        binding.calProgressBar.showIndeterminateHorizontal()
                        "Uploading calibration images"
                    }
                    "packing" -> {
                        binding.calProgressBar.showIndeterminateHorizontal()
                        "Packing calibration ZIP"
                    }
                    "uploading_zip" -> {
                        binding.calProgressBar.showIndeterminateHorizontal()
                        "Uploading calibration ZIP"
                    }
                    "upload_complete" -> {
                        binding.calProgressBar.setDeterminateProgress(1, 1)
                        "Calibration ZIP uploaded"
                    }
                    "upload_failed" -> {
                        binding.calProgressBar.setDeterminateProgress(0, 1)
                        "Calibration ZIP upload failed"
                    }
                    "uploading_metadata" -> {
                        binding.calProgressBar.showIndeterminateHorizontal()
                        "Uploading calibration metadata"
                    }
                    else -> stage.replace('_', ' ')
                }
                if (stageKey != "calibrating") {
                    calInfoLine = ""
                }
                if (stage.equals("calibrating", true) && !stageNorm.isNaN()) {
                    calInfoLine = "norm ${String.format(Locale.US, "%.2f", stageNorm)}"
                }
                updateCalProgressText(serverPercent)
            }
        }

        onSocketEvent("cal_progress") { payload ->
            val j = payload as? JSONObject ?: return@onSocketEvent
            val channelIndexPayload = j.optInt("channel_index", 0)
            val totalChannelsPayload = j.optInt("total_channels", deviceChannelCount)
            val orderTotalPayload = j.optInt("order_total", totalChannelsPayload).coerceAtLeast(1)
            val serverPercentPayload = j.optInt("percent_complete", -1).takeIf { it in 0..100 }
            val orderedDonePayload = when {
                j.has("completed") -> j.optInt("completed", 0)
                j.has("order_position") -> j.optInt("order_position", 0)
                else -> channelIndexPayload + 1
            }.coerceIn(0, orderTotalPayload)
            val wavelengthPayload = j.optInt("wavelength_nm", -1)
            val averagePayload = j.optDouble("average_intensity", -1.0)
            val normPrevPayload = j.optDouble("led_norm_prev", -1.0)
            val normNewPayload = j.optDouble("led_norm_new", -1.0)
            vm.updateCalibrationProgress(
                channelIndex      = channelIndexPayload,
                totalChannels     = totalChannelsPayload,
                wavelengthNm      = wavelengthPayload,
                averageIntensity  = averagePayload,
                normPrev          = normPrevPayload,
                normNew           = normNewPayload,
            )
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                // Ensure we're marked busy during cal, in case this was Pi-initiated
                isCalibratingOngoing = true
                vm.isCalibrating.value = true
                setUiBusy(true)

                refreshCalExpectedImages()
                binding.calProgressBar.visibility = View.VISIBLE
                binding.calProgressText.visibility = View.VISIBLE
                binding.calProgressBar.setDeterminateProgress(orderedDonePayload, orderTotalPayload)
                calStageLine = if (wavelengthPayload > 0) {
                    "Calibrating $wavelengthPayload nm"
                } else {
                    "Calibrating"
                }

                val frameMean = j.optDouble("frame_mean_dn", Double.NaN)
                val saturatedPct = j.optDouble("saturated_pct", Double.NaN)
                calInfoLine = buildString {
                    append("iteration ${j.optInt("iteration", 0)}")
                    if (frameMean.isFinite()) append(String.format(Locale.US, " | image avg %.1f DN (%.1f%%)", frameMean, frameMean / 255.0 * 100.0))
                    if (averagePayload >= 0.0) append(String.format(Locale.US, " | metric %.1f DN", averagePayload))
                    if (normNewPayload >= 0.0) append(String.format(Locale.US, " | norm %.4f", normNewPayload))
                    if (saturatedPct >= 1.0) append(String.format(Locale.US, " | saturated %.1f%%", saturatedPct))
                }
                updateCalProgressText(serverPercentPayload)
            }
        }

        onSocketEvent("cal_uploaded") { payload ->
            val j = payload as? JSONObject ?: return@onSocketEvent
            val imageType = j.optString("image_type", "")
            val fileName = j.optString("file", "")
            val uploaded = j.optBoolean("uploaded", true)
            val zippedImages = j.optInt("images", 0)
            val isDark = imageType.equals("dark", true) || fileName.contains("dark", true)
            if (isDark) calDarkFrameSeen = true
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                if (imageType.equals("zip", true)) {
                    val imageCount = zippedImages.coerceAtLeast(calUploadTotalImages.coerceAtLeast(calTotalChannels))
                    calUploadedImages = if (uploaded) imageCount else 0
                    calUploadTotalImages = imageCount
                    binding.calProgressBar.setDeterminateProgress(calUploadedImages, imageCount)
                    binding.calProgressBar.visibility = View.VISIBLE
                    binding.calProgressText.visibility = View.VISIBLE
                    calStageLine = if (uploaded) "Calibration ZIP uploaded" else "Calibration ZIP upload failed"
                    calInfoLine = if (uploaded) "$imageCount images" else ""
                    updateCalProgressText()
                    return@runOnViewThread
                }

                if (uploaded) {
                    calUploadedImages += 1
                    if (isDark) calDarkImagesUploaded += 1
                }
                val totalFromPlan = calUploadTotalImages.coerceAtLeast(calTotalChannels.coerceAtLeast(1))
                calUploadTotalImages = totalFromPlan
                binding.calProgressBar.showIndeterminateHorizontal()
                binding.calProgressText.visibility = View.VISIBLE
                calStageLine = "Uploading calibration images"
                updateCalProgressText()
            }
        }

        // cal_complete
        onSocketEvent("cal_complete") { payload ->
            val j = payload as? JSONObject
            val norms = j?.optJSONArray("led_norms")
            vm.completeCalibration(norms?.let { values -> List(values.length()) { values.optDouble(it) } })
            val appContext = context?.applicationContext ?: return@onSocketEvent
            val runId = j?.optString("session_id").orEmpty()
            val results = j?.optJSONArray("results")
            if (runId.isNotBlank() && results != null && results.length() > 0) {
                // Save results even when the HTTP metadata upload was interrupted.
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        com.example.msiandroidapp.data.AppDatabase.getDatabase(appContext)
                            .calibrationDao().upsertCalibrationMetadata(
                                runId, norms?.toString(), results.toString(),
                                null, null, null, null, j?.optString("ts_utc")
                            )
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not persist calibration results", e)
                    }
                }
            }
            if (norms != null && norms.length() == deviceChannelCount) {
                val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
                prefs.edit().putString("led_norms_json", norms.toString()).apply()
            }
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                refreshCalExpectedImages()
                binding.calProgressBar.setDeterminateProgress(binding.calProgressBar.max, binding.calProgressBar.max)
                calCooldownUntil = now() + 1_500L
                endCalibrationUi("Calibration complete")
                // Nudge a fresh state from Pi, but UI is already unlocked
                PiSocketManager.emit("get_state", JSONObject())
            }
        }

// cal_error
        onSocketEvent("cal_error") { payload ->
            val message = (payload as? JSONObject)
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "Calibration aborted"
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                vm.failCalibration()
                calCooldownUntil = now() + 1_500L
                if (binding.calProgressBar.isIndeterminate) binding.calProgressBar.isIndeterminate = false
                endCalibrationUi(message)
                PiSocketManager.emit("get_state", JSONObject())
            }
        }



        // --- PMFI SOCKET EVENT BINDINGS ---
        onSocketEvent("pmfi.plan") { payload ->
            val j = payload as JSONObject
            val totalFrames  = j.optInt("total_frames", 0)
            val sectionCount = j.optInt("section_count", 0)

            // model reset
            vm.pmfiTotalFrames.postValue(totalFrames)
            vm.pmfiDoneFrames.postValue(0)
            vm.pmfiPercent.postValue(0)
            vm.pmfiSectionIndex.postValue(0)
            vm.pmfiSectionCount.postValue(sectionCount)
            vm.pmfiSectionDone.postValue(0)
            vm.pmfiSectionTotal.postValue(0)
            vm.pmfiSectionPercent.postValue(0)
            vm.pmfiSectionInfo.postValue(null)
            vm.pmfiCurrentSection.postValue(null)
            vm.pmfiSectionState.postValue(null)
            vm.pmfiComplete.postValue(false)

            // rollover guards
            pmfiCumOffset = 0
            pmfiLastTot   = 0
            lastSectionIndexForTot = -1
            lastSecDoneForTot = 0

            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                // hide any AMSI remnants
                if (binding.captureProgressBar.isIndeterminate) binding.captureProgressBar.isIndeterminate = false
                binding.captureProgressBar.progress = 0
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.text = ""
                binding.captureProgressText.visibility = View.GONE
                isCaptureOngoing = false
                vm.isCapturing.value = false
                vm.imageCount.value = 0
                if (binding.switchCameraPreview.isChecked) triggerButton("SW4")
                clearPreviewSwitchAndCanvas()
                // show PMFI UI
                resetPmfiUi(hide = false)
                isPmfiRunning = true
                setUiBusy(true)
                setPmfiButtonBusy(true)
                binding.pmfiStartBtn.text = "PMFI running…"
                showPmfiUi()
            }
        }

        onSocketEvent("pmfi.stage") { payload ->
            val j = payload as JSONObject
            vm.pmfiCurrentSection.postValue(j.optString("section", null))
            vm.pmfiSectionState.postValue(j.optString("state", ""))
        }

        onSocketEvent("pmfi.progress") { payload ->
            val j = payload as JSONObject

            val secIdx0  = j.optInt("section_index", 0)
            val secDone  = j.optInt("section_done", j.optInt("frame_idx", 0))
            val secTotal = j.optInt("section_frames", 0)
            val rawTot   = j.optInt("total_done", 0)
            val totAll   = j.optInt("total_frames", 0)

            val hasValidSecTotal = secTotal > 0
            val finishedPrevZip  = hasValidSecTotal && lastSecDoneForTot >= (secTotal - 1)
            val startedNewZipOrSection =
                (secIdx0 > lastSectionIndexForTot) || (secDone == 0 && finishedPrevZip)

            if (rawTot < pmfiLastTot && startedNewZipOrSection) {
                pmfiCumOffset += pmfiLastTot
            }
            pmfiLastTot = rawTot
            lastSectionIndexForTot = secIdx0
            lastSecDoneForTot = secDone

            val safeTotAll = totAll.coerceAtLeast(0)
            val safeRawTot = rawTot.coerceAtLeast(0)
            val totDoneCumulative = (safeRawTot + pmfiCumOffset).coerceAtMost(safeTotAll)

            val secPct = if (secTotal > 0)
                ((secDone.toDouble() / secTotal) * 100).toInt() else 0
            val totPct = if (safeTotAll > 0)
                ((totDoneCumulative.toDouble() / safeTotAll) * 100).toInt() else 0

            vm.pmfiCurrentSection.postValue(j.optString("section", null))
            vm.pmfiSectionIndex.postValue(secIdx0)
            vm.pmfiSectionDone.postValue(secDone)
            vm.pmfiSectionTotal.postValue(secTotal)
            vm.pmfiSectionPercent.postValue(secPct)
            vm.pmfiSectionInfo.postValue(j.optString("section_info", null))

            vm.pmfiDoneFrames.postValue(totDoneCumulative)
            vm.pmfiTotalFrames.postValue(safeTotAll)
            vm.pmfiPercent.postValue(totPct)
        }

        onSocketEvent("pmfi.sectionUploaded") { payload ->
            val j = payload as JSONObject
            val section = j.optString("section", "")
            val bytes   = j.optLong("bytes", -1L)
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                val human = if (bytes > 0) {
                    String.format(
                        Locale.getDefault(),
                        "%.1f MB",
                        bytes / (1024f * 1024f)
                    )
                } else {
                    "uploaded"
                }
                Toast.makeText(requireContext(), "$section $human", Toast.LENGTH_SHORT).show()
            }
        }

        onSocketEvent("pmfi.log") { payload ->
            vm.pmfiLogLine.postValue((payload as JSONObject).optString("line"))
        }

        onSocketEvent("pmfi.complete") { payload ->
            val ok = (payload as JSONObject).optBoolean("ok", true)
            vm.pmfiComplete.postValue(ok)
            vm.pmfiPercent.postValue(100)

            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                pmfiCumOffset = 0
                pmfiLastTot   = 0
                isPmfiRunning = false
                setUiBusy(false)
                setPmfiButtonBusy(false)
                binding.pmfiStartBtn.text = "Start PMFI"
            }
        }

        // ===== Initial render / idle defaults =====
        renderEnv(null, null, null)
        updateConnUi(null)              // grey dot, "Status: Unknown"
        resetPmfiUi(hide = true)
        setUiBusy(false)

        // ===== Restore saved IP, connect if known, ask for initial state/battery =====
        restoreSavedIp()
        if (currentIp.isNotEmpty()) {
            PiSocketManager.setBaseUrl(currentIp)
            PiSocketManager.connect(::onPreviewImage, ::onStateUpdate)
            PiSocketManager.emit("get_state", JSONObject())
            pollBatteryOnce()
        }

        // ===== connection-state listener (socket layer high-level up/down) =====
        PiSocketManager.setConnectionStateListener { connected ->
            if (!isAdded) return@setConnectionStateListener
            runOnViewThread {
                if (connected) {
                    updateConnUi(true)
                    fetchDeviceCapabilities()
                } else {
                    // model -> idle
                    vm.resetToIdle()

                    // UI -> fresh, navigate to start tab
                    resetUiToFreshState()
                    (requireActivity() as? MainActivity)?.goToStartPage()
                }
            }
        }

        // ===== Restore any in-flight AMSI capture UI from ViewModel (rotation, etc.) =====
        val restoredCount = vm.imageCount.value ?: 0
        val restoredImgs  = vm.capturedBitmaps.value ?: List(deviceChannelCount) { null }
        if (restoredCount in 1..deviceChannelCount || restoredImgs.any { it != null }) {
            startImageGrid()
            updateGrid(restoredImgs)
            binding.captureProgressBar.setDeterminateProgress(restoredCount, amsiCaptureTotal)
            binding.captureProgressText.text = "Capturing on MFi: $restoredCount/$amsiCaptureTotal"
            binding.captureProgressBar.visibility = View.VISIBLE
            binding.captureProgressText.visibility = View.VISIBLE
        }

        // ===== LiveData observers → keep UI reactive =====
        vm.capturedBitmaps.observe(viewLifecycleOwner) {
            if (amsiSocketPreviewEnabled || mode == PreviewMode.AMSI_GRID) {
                updateGrid(it ?: List(deviceChannelCount) { null })
            }
        }

        vm.imageCount.observe(viewLifecycleOwner) { c ->
            binding.captureProgressText.text = "Capturing on MFi: $c/$amsiCaptureTotal"
            when {
                c in 1 until amsiCaptureTotal -> {
                    binding.captureProgressBar.setDeterminateProgress(c, amsiCaptureTotal)
                    binding.captureProgressBar.visibility = View.VISIBLE
                    binding.captureProgressText.visibility = View.VISIBLE
                }
                c == amsiCaptureTotal -> {
                    binding.captureProgressBar.showIndeterminateHorizontal()
                    binding.captureProgressText.text = "Uploading AMSI ZIP..."
                    binding.captureProgressText.visibility = View.VISIBLE
                }
                else -> {
                    if (binding.captureProgressBar.isIndeterminate) binding.captureProgressBar.isIndeterminate = false
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                }
            }
        }

        // ===== PMFI LiveData → UI =====
        // current section
        vm.pmfiCurrentSection.observe(viewLifecycleOwner) { s ->
            binding.pmfiSectionLabel.text =
                s?.let { "Current section: $it" } ?: "PMFI idle"
        }

        // overall %
        vm.pmfiPercent.observe(viewLifecycleOwner) { p ->
            val v = p ?: 0
            binding.pmfiProgressBar.setProgressFast(v)
            if (binding.pmfiProgressBar.visibility != View.VISIBLE && v > 0) {
                binding.pmfiProgressBar.visibility = View.VISIBLE
            }
        }

        // overall counter text
        vm.pmfiDoneFrames.observe(viewLifecycleOwner) { done ->
            val total = vm.pmfiTotalFrames.value ?: 0
            val d     = done ?: 0
            val pct   = if (total > 0) (d * 100 / total) else 0
            binding.pmfiCounter.text = "$d / $total (${pct}%)"
        }

        // per-section stage/state
        vm.pmfiSectionState.observe(viewLifecycleOwner) { st ->
            binding.pmfiStageLabel.text = st ?: ""
        }
        vm.pmfiSectionInfo.observe(viewLifecycleOwner) { info ->
            binding.tvSectionInfo?.text = info ?: "—"
        }

        var lastSectionIndex = -1
        vm.pmfiSectionIndex.observe(viewLifecycleOwner) { idx0 ->
            val idx   = idx0 ?: 0
            val count = vm.pmfiSectionCount.value ?: 0
            binding.tvSectionsCount?.text = "Sections: ${idx + 1}/$count"

            if (idx != lastSectionIndex) {
                // new section → zero local per-zip bar immediately
                binding.pbSection?.setProgressFast(0)
                binding.tvSectionFrames?.text = "0/0 (0%) in this zip"
                lastSectionIndex = idx
            }
        }

        vm.pmfiSectionDone.observe(viewLifecycleOwner) { done ->
            val total = vm.pmfiSectionTotal.value ?: 0
            val d     = done ?: 0
            val pct   = if (total > 0) (d * 100 / total) else 0
            binding.pbSection?.max = 100
            binding.pbSection?.setProgressFast(pct)
            binding.tvSectionFrames?.text = "$d/$total (${pct}%) in this zip"
        }

        vm.pmfiComplete.observe(viewLifecycleOwner) { ok ->
            if (ok == true) {
                binding.pmfiStageLabel.text = "Complete"
                binding.pmfiProgressBar.setProgressFast(100)

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    if (!isAdded) return@launch
                    isPmfiRunning = false
                    setUiBusy(false)
                    setPmfiButtonBusy(false)
                    binding.pmfiStartBtn.text = "Start PMFI"
                    resetPmfiUi(hide = true)
                }
            }
        }
    }
    private fun kickPreviewResume(maxMs: Long = 5000L) {
        // don't start two loops
        resumeJob?.cancel()
        resumeJob = viewLifecycleOwner.lifecycleScope.launch {
            resumePreviewPending = true
            val start = System.currentTimeMillis()
            while (isActive) {
                // bail if user started something else or disconnected
                if (!isPiConnected || isGlobalBusy()) break

                // already on? we're done
                if (lastSw4FromServer) break

                // try to set ON and then nudge state
                runCatching { ensurePreviewSet(true, timeoutMs = 800) }
                PiSocketManager.emit("get_state", JSONObject())

                // stop conditions
                if (lastSw4FromServer) break
                if (System.currentTimeMillis() - start > maxMs) break

                delay(300)
            }
            resumePreviewPending = false
        }
    }

    private fun ProgressBar.setProgressFast(value: Int) {
        if (Build.VERSION.SDK_INT >= 24) this.setProgress(value, /*animate=*/false)
        else this.progress = value
    }
    // --- PMFI UI helpers ---
    private fun resetPmfiUi(hide: Boolean = true) {
        // Global
        binding.pmfiProgressBar.max = 100
        binding.pmfiProgressBar.setProgressFast(0)
        binding.pmfiCounter.text = "0 / 0 (0%)"
        binding.pmfiSectionLabel.text = "PMFI idle"
        binding.pmfiStageLabel.text = ""
        if (hide) binding.pmfiProgressBar.visibility = View.GONE else binding.pmfiProgressBar.visibility = View.VISIBLE

        // Per-section
        binding.pbSection?.max = 100
        binding.pbSection?.setProgressFast(0)
        binding.tvSectionFrames?.text = "0/0 (0%) in this section"
        binding.tvSectionInfo?.text = "—"
        binding.tvSectionsCount?.text = "Sections: 0/0"
    }

    private fun showPmfiUi() {
        binding.pmfiProgressBar.visibility = View.VISIBLE
        binding.pbSection?.visibility = View.VISIBLE
        // Ensure visible text right away
        if (binding.pmfiStageLabel.text.isNullOrBlank()) binding.pmfiStageLabel.text = "Starting…"
    }
    // Returns true if the instrument should be treated as BUSY (user must not start anything else)
    private fun isGlobalBusy(): Boolean {
        if (modeTransitionInProgress || serverTransitionInProgress) return true
        if (now() < calStartGraceUntil) return true
        val inCalCooldown = now() < calCooldownUntil
        val calFlag = (isCalibratingOngoing || vm.isCalibrating.value == true) && !inCalCooldown
        return isCaptureOngoing || isPmfiRunning || calFlag
    }


    private fun resetUiToFreshState() {
        isPiConnected = false
        isConnecting = false
        serverTransitionInProgress = false
        modeHandshakeVersion = 0

        // Connection strip
        binding.piConnectionStatus.text = "Status: Unknown"
        binding.piConnectionDot.setBackgroundResource(R.drawable.circle_grey)
        renderTopConnectionChip(null)

        // ---- Clear battery chip ----
        binding.chipBattery.text = "— %"
        binding.chipBattery.setChipIconResource(R.drawable.ic_battery_unknown_24)

        // ---- Clear env chips ----
        showingCpuTemp = false
        latestCpuTempC = null
        latestCpuTempIso = null
        lastCpuTempEventAt = 0L
        binding.topTempChip.text = "Temp: —"
        binding.topHumChip.text  = "RH: —"

        // Keep legacy hidden labels in sync too (just so nothing downstream explodes)
        binding.envTempText.text     = "Temp: —"
        binding.envHumidityText.text = "RH: —"

        // Last button & switches
        binding.lastPiButtonText.text = "MFi Button Pressed: --"
        binding.switchCameraPreview.isChecked = false
        binding.switchCameraPreview.isEnabled = false
        amsiSocketPreviewEnabled = false
        amsiSocketPreviewToggleInFlight = false
        binding.switchAmsiSocketPreview.setOnCheckedChangeListener(null)
        binding.switchAmsiSocketPreview.isChecked = false
        binding.switchAmsiSocketPreview.isEnabled = false
        binding.switchAmsiSocketPreview.alpha = 0.4f
        attachAmsiSocketPreviewToggleListener()
        wasPreviewOnBeforeAmsi = false

        // AMSI progress
        if (binding.captureProgressBar.isIndeterminate) binding.captureProgressBar.isIndeterminate = false
        binding.captureProgressBar.progress = 0
        binding.captureProgressBar.visibility = View.GONE
        binding.captureProgressText.text = ""
        binding.captureProgressText.visibility = View.GONE

        // Calibration progress
        binding.calProgressBar.progress = 0
        if (binding.calProgressBar.isIndeterminate) binding.calProgressBar.isIndeterminate = false
        binding.calProgressBar.visibility = View.GONE
        binding.calProgressText.text = ""
        binding.calProgressText.visibility = View.GONE
        calUploadedImages = 0
        calUploadTotalImages = deviceChannelCount

        // PMFI global
        binding.pmfiProgressBar.progress = 0
        binding.pmfiCounter.text = "0 / 0 (0%)"
        binding.pmfiSectionLabel.text = "PMFI idle"
        binding.pmfiStageLabel.text = ""
        binding.pmfiStartBtn.isEnabled = true
        binding.pmfiStartBtn.alpha = 1f
        binding.pmfiStartBtn.text = "Start PMFI"

        // PMFI per-section
        binding.pbSection?.progress = 0
        binding.tvSectionFrames?.text = "0/0 (0%) in this section"
        binding.tvSectionInfo?.text = "—"
        binding.tvSectionsCount?.text = "Sections: 0/0"

        // Preview area
        clearPreview()
        view?.findViewById<android.widget.ScrollView>(R.id.controls_scrollview)?.scrollTo(0, 0)

        // Inputs / buttons
        binding.setIpButton.isEnabled = true
        binding.ipAddressInput.isEnabled = true
        binding.buttonDisconnect.isEnabled = false
        binding.buttonStartAmsi.isEnabled = false
        binding.buttonCalibrate.isEnabled = false
        binding.buttonShutdown.isEnabled = false
        binding.buttonFactoryReset.isEnabled = false
    }

    private val pmfiEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val txt = res.data?.getStringExtra(PmfiEditorActivity.EXTRA_RESULT_TEXT) ?: return@registerForActivityResult
            binding.pmfiIniEdit.setText(txt)
        }
    }
    private val pmfiIniFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val displayName = queryDisplayName(uri) ?: "pmfi_upload.ini"
        val lowerName = displayName.lowercase(Locale.getDefault())
        if (!lowerName.endsWith(".ini") && !lowerName.endsWith(".txt")) {
            toast("Please select a .ini or .txt file")
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val iniText = readUriText(uri).trim()
            if (iniText.isBlank()) {
                toast("Selected file is empty")
                return@launch
            }
            binding.pmfiIniEdit.setText(iniText)
            uploadPmfiIniText(iniText, displayName)
        }
    }
    override fun onResume() {
        super.onResume()
        startPolling()
        if (currentIp.isNotEmpty()) PiSocketManager.emit("get_state", JSONObject())
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroyView() {
        // Cancel anything that could touch the old view
        stopPolling()
        disconnectJob?.cancel()
        disconnectJob = null
        socketSubscriptions.forEach { (event, handler) -> PiSocketManager.off(event, handler) }
        socketSubscriptions.clear()
        PiSocketManager.clearViewCallbacks(::onPreviewImage, ::onStateUpdate)
        PiSocketManager.setConnectionStateListener(null)
        liveImage?.setImageDrawable(null)
        liveImage = null
        _binding = null
        super.onDestroyView()
    }
    private suspend fun stopPreviewAndAwaitAck(timeoutMs: Long = 2500L) {
        if (!previewActive && !binding.switchCameraPreview.isChecked) return
        PiSocketManager.emit("get_state", JSONObject())
        if (previewActive || binding.switchCameraPreview.isChecked) triggerButton("SW4")
        val start = System.currentTimeMillis()
        while ((previewActive || binding.switchCameraPreview.isChecked) &&
            (System.currentTimeMillis() - start) < timeoutMs) {
            delay(40)
        }
    }

    // top-level in ControlFragment
    // ControlFragment — REPLACE the whole function
    private var calCooldownUntil: Long = 0L
    private fun now() = System.currentTimeMillis()

    private fun endCalibrationUi(reasonToast: String? = null) {
        // ---- Clear model flags ----
        vm.isCalibrating.value = false
        isCalibratingOngoing = false

        // ---- Hide cal widgets ----
        if (binding.calProgressBar.isIndeterminate) binding.calProgressBar.isIndeterminate = false
        binding.calProgressBar.progress = 0
        binding.calProgressBar.visibility = View.GONE
        binding.calProgressText.text = ""
        binding.calProgressText.visibility = View.GONE
        calUploadedImages = 0
        calUploadTotalImages = deviceChannelCount

        // ---- Clear any residual busy from other modes that calibration might have set ----
        isCaptureOngoing = false
        isPmfiRunning = false
        vm.isCapturing.value = false

        // ---- Re-enable UI immediately; also set a short cooldown to ignore stale server flags ----
        calCooldownUntil = now() + 1_500L   // mask lingering "calibrating=true"/"busy=true"
        setUiBusy(false)

        // Preview toggle becomes available again if connected and no other jobs run
        val canTogglePreview = isPiConnected && !isGlobalBusy()
        binding.switchCameraPreview.isEnabled = canTogglePreview
        binding.switchCameraPreview.alpha     = if (canTogglePreview) 1f else 0.4f

        // Calibrate button back on when connected
        binding.buttonCalibrate.isEnabled = isPiConnected
        binding.buttonCalibrate.alpha     = if (isPiConnected) 1f else 0.4f

        // Final safety: after the cooldown window, force a clean unlock if anything is still sticky.
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1_600)
            if (!isAdded) return@launch
            setUiBusy(false)
        }

        reasonToast?.let { toast(it) }
    }

    // ControlFragment — ADD this helper
    private fun dropStaleBusyFlagsFromCal(
        busyNowFromServer: Boolean,
        calNowFromServer: Boolean,
        pmfiNowFromServer: Boolean
    ): Pair<Boolean, Boolean> {
        var busyNow = busyNowFromServer
        var calNow  = calNowFromServer

        if (now() < calCooldownUntil) {
            // During the cooldown, ignore cal+busy unless something else is actually running
            calNow = false
            if (!pmfiNowFromServer && !isCaptureOngoing) {
                busyNow = false
            }
        }
        return busyNow to calNow
    }



    // ===== UI setup =====
    private fun setupButtons() {
        // IP connect
        binding.setIpButton.setOnClickListener {
            val ip = binding.ipAddressInput.text.toString().trim()
            if (ip.isBlank()) {
                updateConnUi(null)
                return@setOnClickListener
            }
            setBaseUrls(ip)
            saveIp(ip)
            isConnecting = true
            updateConnUi(null)
            connectSocket()
            checkStatus(ip)
        }
        binding.topTempChip.setOnClickListener {
            showingCpuTemp = !showingCpuTemp
            renderTemperatureChip()
            if (showingCpuTemp) pollCpuTempOnce()
        }
        binding.pmfiExpandBtn.setOnClickListener {
            val ctx = requireContext()
            val intent = Intent(ctx, PmfiEditorActivity::class.java).apply {
                putExtra(PmfiEditorActivity.EXTRA_TEXT, binding.pmfiIniEdit.text?.toString().orEmpty())
            }
            pmfiEditorLauncher.launch(intent)
        }
        binding.pmfiUploadIniBtn.setOnClickListener {
            if (!isPiConnected) { toast("Not connected"); return@setOnClickListener }
            val iniText = binding.pmfiIniEdit.text?.toString()?.trim().orEmpty()
            if (iniText.isBlank()) {
                pmfiIniFilePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
            } else {
                uploadPmfiIniText(iniText, "pmfi_upload.ini")
            }
        }
        // Disconnect
        binding.buttonDisconnect.setOnClickListener {
            when {
                isPmfiRunning && isCaptureOngoing -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Capture & PMFI running")
                        .setMessage("Disconnecting now will abort capture and PMFI. Are you sure you want to disconnect?")
                        .setPositiveButton("Disconnect") { _, _ -> forceDisconnect() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                isPmfiRunning -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle("PMFI running")
                        .setMessage("Disconnecting will interrupt uploads and abort PMFI. Disconnect anyway?")
                        .setPositiveButton("Disconnect") { _, _ -> forceDisconnect() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                isCaptureOngoing -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Image capture in progress")
                        .setMessage("Disconnecting will abort the current AMSI capture. Disconnect anyway?")
                        .setPositiveButton("Disconnect") { _, _ -> forceDisconnect() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                else -> forceDisconnect()
            }
        }


        // Preview toggle (SW4)
        // Preview toggle (SW4)
        attachPreviewToggleListener()
        attachAmsiSocketPreviewToggleListener()
        binding.switchFanOverride.setOnCheckedChangeListener { _, checked ->
            if (fanControlAvailable && !fanRequestInFlight) setFanOverride(checked)
        }


        binding.buttonDiagnostics.setOnClickListener {
            if (isPiConnected && !isGlobalBusy()) {
                startActivity(Intent(requireContext(), com.example.msiandroidapp.ui.diagnostics.DiagnosticsActivity::class.java))
            } else toast("Connect to an idle instrument first")
        }

        binding.buttonAmsiChannels.setOnClickListener {
            if (isPiConnected && capabilitiesReady && !isGlobalBusy()) showAmsiChannelDialog {}
        }

        // AMSI (SW2)
        binding.buttonStartAmsi.setOnClickListener {
            if (!capabilitiesReady) { toast("Waiting for device channel information"); return@setOnClickListener }
            if (!isPiConnected) { toast("MFi not connected"); return@setOnClickListener }
            val gotLock = tryBeginBusy("amsi"); if (!gotLock) { toast("Busy"); return@setOnClickListener }

            viewLifecycleOwner.lifecycleScope.launch {
                // 1) Remember if preview (warming) was ON before we stop it
                wasPreviewOnBeforeAmsi = lastSw4FromServer || binding.switchCameraPreview.isChecked || previewActive

                val useAmsiGrid = ensureAmsiSocketPreviewEnabledForCapture()

                // 3) Start AMSI UI
                vm.isCapturing.value = true
                if (useAmsiGrid) {
                    startImageGrid()
                } else {
                    clearPreview()
                }
                startCaptureUi()
                amsiCaptureTotal = selectedAmsiChannels.size
                vm.prepareCapture(deviceChannelCount)
                vm.imageCount.value = 0
                binding.buttonStartAmsi.isEnabled = false
                binding.buttonStartAmsi.alpha = 0.4f

                runCatching {
                    performModeTransition("amsi", channels = selectedAmsiChannels.sorted())
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    toast("Failed to start capture: ${e.localizedMessage}")
                    isCaptureOngoing = false
                    vm.isCapturing.value = false
                    currentAmsiStage = ""
                    clearPreview()
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                    setUiBusy(false)
                    binding.buttonStartAmsi.isEnabled = true
                    binding.buttonStartAmsi.alpha = 1f
                }
            }
        }


// CAL (SW3)
        binding.buttonCalibrate.setOnClickListener {
            if (!isPiConnected) { toast("Not connected"); return@setOnClickListener }
            val gotLock = tryBeginBusy("cal"); if (!gotLock) { toast("Busy"); return@setOnClickListener }

            calStartGraceUntil = now() + 1200L
            resetCalExpectedImages()
            calStageLine = ""
            calInfoLine = ""
            vm.startCalibration(totalChannels = deviceChannelCount)
            showCalUi(true)
            binding.buttonCalibrate.isEnabled = false
            binding.buttonCalibrate.alpha = 0.4f

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    performModeTransition("calibration")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    endCalibrationUi("Calibration could not start: ${e.localizedMessage}")
                }
            }
        }

// PMFI
        binding.pmfiStartBtn.setOnClickListener {
            if (!isPiConnected) { toast("Not connected"); return@setOnClickListener }
            val iniText = binding.pmfiIniEdit.text?.toString()?.trim().orEmpty()
            if (iniText.isBlank()) { toast("Paste an INI first"); return@setOnClickListener }
            val gotLock = tryBeginBusy("pmfi"); if (!gotLock) { toast("Busy"); return@setOnClickListener }

            binding.pmfiStartBtn.isEnabled = false
            binding.pmfiStartBtn.isClickable = false
            binding.pmfiStartBtn.alpha = 0.4f
            binding.pmfiStartBtn.text = "PMFI running…"

            viewLifecycleOwner.lifecycleScope.launch {
                startPmfi(iniText)
            }
        }





        // Shutdown
        binding.buttonShutdown.setOnClickListener {
            if (currentIp.isEmpty()) { toast("Set IP address first"); return@setOnClickListener }
            if (isPmfiRunning) { toast("PMFI running – wait for completion"); return@setOnClickListener }
            AlertDialog.Builder(requireContext())
                .setTitle("Shutdown System")
                .setMessage("Are you sure you want to shut down the MFi device?")
                .setPositiveButton("Shutdown") { _, _ -> sendShutdown() }
                .setNegativeButton("Cancel", null).show()
        }

        binding.buttonFactoryReset.setOnClickListener {
            if (currentIp.isEmpty()) { toast("Set IP address first"); return@setOnClickListener }
            if (isPmfiRunning) { toast("PMFI running – wait for completion"); return@setOnClickListener }
            AlertDialog.Builder(requireContext())
                .setTitle("Factory Reset")
                .setMessage("This will abort current tasks and attempt recovery. Proceed?")
                .setPositiveButton("Factory reset") { _, _ -> sendFactoryReset() }
                .setNegativeButton("Cancel", null).show()
        }

        // Initial progress widgets
        binding.captureProgressBar.setDeterminateProgress(0, 16)
        binding.captureProgressBar.visibility = View.GONE
        binding.captureProgressText.visibility = View.GONE
        binding.switchAmsiSocketPreview.isChecked = amsiSocketPreviewEnabled
        binding.switchAmsiSocketPreview.isEnabled = false
        binding.switchAmsiSocketPreview.alpha = 0.4f
    }
    // REPLACE ENTIRE FUNCTION
    private fun attachPreviewToggleListener() {
        resumeJob?.cancel()
        resumePreviewPending = false
        binding.switchCameraPreview.setOnCheckedChangeListener(null)

        // Prevent double taps while we talk to the Pi
        var toggleInFlight = false

        binding.switchCameraPreview.setOnCheckedChangeListener { _, checked ->
            if (!isPiConnected || isGlobalBusy()) {
                // Snap back to server state when busy/not connected
                binding.switchCameraPreview.setOnCheckedChangeListener(null)
                binding.switchCameraPreview.isChecked = lastSw4FromServer
                attachPreviewToggleListener()
                return@setOnCheckedChangeListener
            }
            if (toggleInFlight) {
                // Ignore repeat toggles until the first one settles
                binding.switchCameraPreview.setOnCheckedChangeListener(null)
                binding.switchCameraPreview.isChecked = !checked
                attachPreviewToggleListener()
                return@setOnCheckedChangeListener
            }

            toggleInFlight = true
            binding.switchCameraPreview.isEnabled = false
            binding.switchCameraPreview.alpha = 0.4f

            viewLifecycleOwner.lifecycleScope.launch {
                requestPreviewSet(checked)
                binding.switchCameraPreview.isEnabled = isPiConnected && !isGlobalBusy()
                binding.switchCameraPreview.alpha =
                    if (binding.switchCameraPreview.isEnabled) 1f else 0.4f
                toggleInFlight = false
            }
        }
    }

    private fun attachAmsiSocketPreviewToggleListener() {
        binding.switchAmsiSocketPreview.setOnCheckedChangeListener(null)
        binding.switchAmsiSocketPreview.isChecked = amsiSocketPreviewEnabled

        binding.switchAmsiSocketPreview.setOnCheckedChangeListener { _, checked ->
            if (!isPiConnected || isGlobalBusy() || amsiSocketPreviewToggleInFlight) {
                binding.switchAmsiSocketPreview.setOnCheckedChangeListener(null)
                binding.switchAmsiSocketPreview.isChecked = amsiSocketPreviewEnabled
                attachAmsiSocketPreviewToggleListener()
                return@setOnCheckedChangeListener
            }
            setAmsiSocketPreviewEnabled(checked)
        }
    }

    private fun syncAmsiSocketPreviewToggle() {
        if (!isPiConnected || currentIp.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = PiApi.api.amsiPreviewStatus()
                val enabled = resp.body()?.enabled
                if (resp.isSuccessful && enabled != null && isAdded) {
                    amsiSocketPreviewEnabled = enabled
                    binding.switchAmsiSocketPreview.setOnCheckedChangeListener(null)
                    binding.switchAmsiSocketPreview.isChecked = enabled
                    attachAmsiSocketPreviewToggleListener()
                    setUiBusy(isGlobalBusy())
                }
            } catch (_: Exception) {
                // Keep the local default; the toggle will retry on user action.
            }
        }
    }

    private fun setAmsiSocketPreviewEnabled(enabled: Boolean) {
        amsiSocketPreviewToggleInFlight = true
        binding.switchAmsiSocketPreview.isEnabled = false
        binding.switchAmsiSocketPreview.alpha = 0.4f

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = PiApi.api.setAmsiPreview(enabled)
                val confirmed = resp.body()?.enabled
                if (resp.isSuccessful && confirmed != null) {
                    amsiSocketPreviewEnabled = confirmed
                    toast(
                        if (confirmed) "AMSI image grid enabled - capture will be slower"
                        else "AMSI image grid disabled for faster capture"
                    )
                } else {
                    toast("AMSI preview toggle failed: ${resp.code()}")
                }
            } catch (e: Exception) {
                toast("Network error: ${e.localizedMessage}")
            } finally {
                amsiSocketPreviewToggleInFlight = false
                if (!isAdded) return@launch
                binding.switchAmsiSocketPreview.setOnCheckedChangeListener(null)
                binding.switchAmsiSocketPreview.isChecked = amsiSocketPreviewEnabled
                attachAmsiSocketPreviewToggleListener()
                setUiBusy(isGlobalBusy())
            }
        }
    }

// ADD — robust preview control + waiting for Pi ack

    private suspend fun ensureAmsiSocketPreviewEnabledForCapture(): Boolean {
        if (!amsiSocketPreviewEnabled && !binding.switchAmsiSocketPreview.isChecked) {
            return false
        }

        return try {
            val resp = PiApi.api.setAmsiPreview(true)
            val confirmed = resp.body()?.enabled == true
            amsiSocketPreviewEnabled = confirmed
            if (isAdded) {
                binding.switchAmsiSocketPreview.setOnCheckedChangeListener(null)
                binding.switchAmsiSocketPreview.isChecked = confirmed
                attachAmsiSocketPreviewToggleListener()
            }
            confirmed
        } catch (e: Exception) {
            if (isAdded) {
                toast("AMSI image grid could not be enabled: ${e.localizedMessage}")
            }
            false
        }
    }

    // Ask the Pi to set preview ON/OFF and wait for ack via 'sw4' in state updates.
// Returns true if the Pi reported the requested state before timeout.
    private fun markPreviewRequest(targetOn: Boolean, windowMs: Long = previewRequestAckWindowMs) {
        previewRequestedState = targetOn
        previewRequestPendingUntil = System.currentTimeMillis() + windowMs
    }

    private fun clearPreviewRequest() {
        previewRequestedState = null
        previewRequestPendingUntil = 0L
    }

    private fun isPreviewRequestPending(nowMs: Long = System.currentTimeMillis()): Boolean {
        return previewRequestedState != null && nowMs < previewRequestPendingUntil
    }

    private suspend fun requestPreviewSet(targetOn: Boolean) {
        ensurePreviewSet(targetOn)
    }

    private suspend fun ensurePreviewSet(targetOn: Boolean, timeoutMs: Long = 20_000L): Boolean {
        return try {
            performModeTransition(if (targetOn) "preview" else "idle")
            previewActive = targetOn
            lastSw4FromServer = targetOn
            clearPreviewRequest()
            binding.switchCameraPreview.setOnCheckedChangeListener(null)
            binding.switchCameraPreview.isChecked = targetOn
            attachPreviewToggleListener()
            if (targetOn) startLivePreview() else showPreviewOffState()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clearPreviewRequest()
            showPreviewOffState()
            toast("Mode change failed: ${e.localizedMessage}")
            PiSocketManager.emit("get_state", JSONObject())
            false
        }
    }

    private suspend fun performModeTransition(
        targetMode: String, channels: List<Int>? = null,
        iniText: String? = null, sessionId: String? = null
    ) {
        val transitionBinding = binding
        check(modeTransitionMutex.tryLock()) { "Another mode change is in progress" }
        modeTransitionInProgress = true
        clearPreviewRequest()
        clearLiveOnly()
        PiSocketManager.discardPendingPreviewFrames()
        setUiBusy(true)
        try {
            if (modeHandshakeVersion < 1) {
                // Verify with the Pi before rejecting a start based on cached state.
                val capabilities = PiApi.api.capabilities()
                check(capabilities.isSuccessful && capabilities.body() != null) {
                    "Could not verify server mode support (HTTP ${capabilities.code()}); reconnect and retry"
                }
                modeHandshakeVersion = capabilities.body()!!.mode_handshake_version
            }
            check(modeHandshakeVersion >= 1) {
                "Connected MFI server reports no mode handshake support; update and restart its server"
            }
            ModeHandshake.start(
                api = PiApi.api, mode = targetMode, channels = channels,
                iniText = iniText, sessionId = sessionId,
                appReady = {
                    if (targetMode in setOf("amsi", "calibration", "pmfi")) {
                        com.example.msiandroidapp.service.UploadForegroundService.awaitReady(requireContext())
                    }
                    PiSocketManager.discardPendingPreviewFrames()
                    if (targetMode == "preview") showPreviewStartingState()
                },
                onPhase = { message ->
                    when (targetMode) {
                        "amsi" -> binding.captureProgressText.text = message
                        "calibration" -> binding.calProgressText.text = message
                        "pmfi" -> binding.pmfiStageLabel.text = message
                        else -> binding.previewSubtitleText.text = message
                    }
                }
            )
        } finally {
            modeTransitionInProgress = false
            modeTransitionMutex.unlock()
            if (_binding === transitionBinding) {
                setUiBusy(isGlobalBusy())
                PiSocketManager.emit("get_state", JSONObject())
            }
        }
    }

    // Convenience: ensure preview is OFF before running a job.
// Returns true if OFF confirmed (or forced locally after timeout).
    private suspend fun killPreviewIfNeededAndWait(timeoutMs: Long = 2_500L): Boolean {
        return if (lastSw4FromServer || binding.switchCameraPreview.isChecked || previewActive) {
            ensurePreviewSet(false, timeoutMs)
        } else true
    }
    // Keep track of the AMSI run we’re receiving
    private var currentAmsiRunId: String? = null
    private var currentAmsiStage: String = ""
    private var amsiCaptureTotal = 16
    private var amsiZipUploadNotified = false
    private val amsiZipBytesByRun = mutableMapOf<String, Long>()

    private fun percentText(done: Int, total: Int, serverPercent: Int? = null): String {
        if (serverPercent != null && serverPercent in 0..100) return "$serverPercent%"
        val safeTotal = total.coerceAtLeast(1)
        val pct = Math.round((done.coerceIn(0, safeTotal) * 100.0) / safeTotal).toInt()
        return "$pct%"
    }

    private fun ProgressBar.setDeterminateProgress(progress: Int, maxValue: Int) {
        if (isIndeterminate) isIndeterminate = false
        max = maxValue.coerceAtLeast(1)
        this.progress = progress.coerceIn(0, max)
    }

    private fun ProgressBar.showIndeterminateHorizontal() {
        if (!isIndeterminate) isIndeterminate = true
        visibility = View.VISIBLE
    }

    private fun startCaptureUi() {
        amsiCaptureTotal = selectedAmsiChannels.size.coerceAtLeast(1)
        amsiZipUploadNotified = false
        currentAmsiStage = "capturing"
        setAmsiCapturing(0, amsiCaptureTotal)
    }
    private fun showAmsiProgress(progress: Int, max: Int = amsiCaptureTotal, text: String) {
        binding.captureProgressBar.setDeterminateProgress(progress, max)
        binding.captureProgressText.text = text
        binding.captureProgressBar.visibility = View.VISIBLE
        binding.captureProgressText.visibility = View.VISIBLE
    }

    private fun setAmsiCapturing(done: Int, total: Int = amsiCaptureTotal, text: String? = null) {
        amsiCaptureTotal = total.coerceAtLeast(1)
        val safeDone = done.coerceIn(0, amsiCaptureTotal)

        showAmsiProgress(
            progress = safeDone,
            max = amsiCaptureTotal,
            text = text ?: "Capturing on MFi: $safeDone/$amsiCaptureTotal (${percentText(safeDone, amsiCaptureTotal)})"
        )

        if (amsiSocketPreviewEnabled) {
            showAmsiGrid()
            binding.amsiGridProgressBar.max = amsiCaptureTotal
            binding.amsiGridProgressBar.progress = safeDone
            binding.amsiGridStatusChip.text = "$safeDone/$amsiCaptureTotal"
        }
    }

    private fun setAmsiUploadingZip(text: String = "Uploading AMSI ZIP...") {
        binding.captureProgressBar.showIndeterminateHorizontal()
        binding.captureProgressText.text = text
        binding.captureProgressText.visibility = View.VISIBLE
    }

    private fun setAmsiUploadComplete(text: String = "AMSI ZIP upload complete") {
        showAmsiProgress(
            progress = amsiCaptureTotal,
            max = amsiCaptureTotal,
            text = text
        )

        if (amsiSocketPreviewEnabled) {
            binding.amsiGridProgressBar.max = amsiCaptureTotal
            binding.amsiGridProgressBar.progress = amsiCaptureTotal
            binding.amsiGridStatusChip.text = "$amsiCaptureTotal/$amsiCaptureTotal"
        }
    }

    private fun observeUploadProgress() {
        var lastHandledRunId: String? = null

        UploadProgressBus.amsiZipBytes.observe(viewLifecycleOwner) { (sessionId, bytes) ->
            if (bytes > 0L) {
                amsiZipBytesByRun[sessionId] = bytes
            }
        }

        UploadProgressBus.uploadProgress.observe(viewLifecycleOwner) { (sessionId, count) ->
            // Ignore replayed/stale emissions from previous app sessions
            if (sessionId == lastHandledRunId && count == amsiCaptureTotal) return@observe

            // Ignore if we’re not currently doing AMSI
            if (isPmfiRunning || (vm.isCalibrating.value == true)) return@observe

            // First image → mark as active run
            if (count in 1..amsiCaptureTotal && currentAmsiRunId == null) currentAmsiRunId = sessionId

            Log.d(TAG, "Upload progress $sessionId : $count")
            vm.imageCount.value = count
            binding.captureProgressBar.setDeterminateProgress(count, 16)
            binding.captureProgressText.text = "Saving images: $count/$amsiCaptureTotal"

            when {
                count in 1..15 -> {
                    vm.isCapturing.value = true
                    binding.captureProgressBar.visibility = View.VISIBLE
                    binding.captureProgressText.visibility = View.VISIBLE
                }

                count == amsiCaptureTotal -> {
                    lastHandledRunId = sessionId
                    vm.isCapturing.value = false
                    binding.captureProgressText.text = "AMSI ZIP upload complete"

                    viewLifecycleOwner.lifecycleScope.launch {
                        val id = currentAmsiRunId ?: sessionId
                        val humanZipSize = amsiZipBytesByRun.remove(id)?.let { formatBytes(it) }
                        // Only show toast if this run actually happened during this session
                        if (isAdded && currentAmsiRunId == sessionId) {
                            toast(
                                if (humanZipSize != null) "AMSI saved ($humanZipSize ZIP)"
                                else "AMSI saved"
                            )
                        }

                        delay(1500)
                        if (!isAdded) return@launch
                        binding.captureProgressBar.visibility = View.GONE
                        binding.captureProgressText.visibility = View.GONE
                        currentAmsiRunId = null
                        clearPreview()
                        if (wasPreviewOnBeforeAmsi) kickPreviewResume()
                        wasPreviewOnBeforeAmsi = false
                        restorePreviewIfNeeded()
                        wasPreviewOnBeforeAmsi = false
                    }
                }

                else -> {
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                }
            }
        }
    }

    // --- Battery telemetry (freshness tracking) ---
    private var lastBatteryEventAt: Long = 0L
    private val batteryPollMs = 10_000L
    private val batteryLowThresholdPct = 20

    private fun hookBatterySocket() {
        // payload is the snapshot (server emits it flat)
        onSocketEvent("battery.update") { payload ->
            val root = payload as org.json.JSONObject
            lastBatteryEventAt = System.currentTimeMillis()
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                renderBatteryFromJson(root)
            }
        }
    }

    private fun hookSystemThrottleSocket() {
        onSocketEvent("system.throttle") { payload ->
            val root = payload as? org.json.JSONObject ?: return@onSocketEvent
            val flags = root.optJSONObject("flags")
            val thermalActive = root.optBoolean("thermal_throttle_active") ||
                (flags?.optBoolean("currently_throttled") == true) ||
                (flags?.optBoolean("soft_temperature_limit_active") == true)
            val thermalOccurred = root.optBoolean("thermal_throttle_occurred")
            val powerActive = root.optBoolean("power_throttle_active")
            val rawHex = root.optString("hex", root.optString("raw", ""))
            val tempC = root.optDoubleOrNull("temp_c")
            val tempText = tempC?.let { String.format(Locale.UK, "%.1f C", it) } ?: "unknown temp"
            if (tempC != null) {
                latestCpuTempC = tempC
                latestCpuTempIso = root.optString("ts_utc").takeIf { it.isNotBlank() }
                lastCpuTempEventAt = System.currentTimeMillis()
                if (isAdded) {
                    runOnViewThread {
                        renderTemperatureChip()
                    }
                }
            }

            if (thermalActive || thermalOccurred || powerActive) {
                Log.w(
                    "ControlFragment",
                    "system.throttle $rawHex temp=$tempText thermalActive=$thermalActive " +
                        "thermalOccurred=$thermalOccurred powerActive=$powerActive"
                )
            } else {
                Log.i("ControlFragment", "system.throttle $rawHex temp=$tempText")
            }

            if (!thermalActive || !isAdded) return@onSocketEvent
            val now = System.currentTimeMillis()
            if (now - lastThermalThrottleToastAt < 60_000L) return@onSocketEvent
            lastThermalThrottleToastAt = now
            runOnViewThread {
                toast("Thermal throttling detected ($tempText)")
            }
        }
    }

    private fun pollBatteryOnce() {
        val ip = currentIp
        if (ip.isBlank()) return
        val req = Request.Builder().url("http://$ip:5000/battery").get().build()
        quickClient.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { /* ignore; socket is primary */ }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val body = it.body?.string().orEmpty()
                    try {
                        val obj = org.json.JSONObject(body)
                        val snap = obj.optJSONObject("battery") ?: return
                        if (!isAdded) return
                        runOnViewThread {
                            renderBatteryFromJson(snap)
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        })
    }

    private fun renderBatteryFromJson(snap: org.json.JSONObject?) {
        if (snap == null) {
            binding.chipBattery.text = "— %"
            binding.chipBattery.setChipIconResource(R.drawable.ic_battery_unknown_24)
            return
        }

        val state   = snap.optString("charging_state", null)?.uppercase() ?: "UNKNOWN"
        val present = if (snap.has("present") && !snap.isNull("present")) snap.optBoolean("present") else null
        val soc     = snap.optIntOrNull("soc_pct")
        val volt    = snap.optDoubleOrNull("voltage_v")
        val indicator = batteryIndicator(state, present, soc, batteryLowThresholdPct)
        val iconRes = when (indicator) {
            BatteryIndicator.CHARGING -> R.drawable.ic_battery_charging_24
            BatteryIndicator.LOW, BatteryIndicator.FAULT -> R.drawable.ic_battery_alert_24
            BatteryIndicator.UNKNOWN -> R.drawable.ic_battery_unknown_24
            BatteryIndicator.NORMAL -> R.drawable.ic_battery_24
        }
        val statusText = when (indicator) {
            BatteryIndicator.CHARGING -> "Charging"
            BatteryIndicator.LOW -> "Low battery, not charging"
            BatteryIndicator.FAULT -> "Battery charger fault"
            BatteryIndicator.UNKNOWN -> "Battery status unavailable"
            BatteryIndicator.NORMAL -> "Not charging"
        }

        // Label: prefer %; else show voltage
        val label = when {
            soc != null -> "$soc%"
            volt != null -> String.format(Locale.UK, "%.2f V", volt)
            else -> "— %"
        }

        binding.chipBattery.text = label
        binding.chipBattery.contentDescription = "$label, $statusText"
        binding.chipBattery.setChipIconResource(iconRes)
        binding.chipBattery.isChipIconVisible = true
        binding.chipBattery.chipIconTint = android.content.res.ColorStateList.valueOf(
            when (indicator) {
                BatteryIndicator.CHARGING -> android.graphics.Color.rgb(46, 125, 50)
                BatteryIndicator.LOW, BatteryIndicator.FAULT -> android.graphics.Color.rgb(198, 40, 40)
                else -> binding.chipBattery.currentTextColor
            }
        )
    }

    // JSON helpers
    private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    // ===== Status polling / connection =====
    private fun setBaseUrls(ip: String) {
        currentIp = ip.trim()
        PiApi.setBaseUrl(currentIp)
        PiSocketManager.setBaseUrl(currentIp)
        PiSocketManager.reconnect()
        PiSocketManager.emit("get_state", JSONObject())
    }

    private fun connectSocket() {
        PiSocketManager.connect(::onPreviewImage, ::onStateUpdate)
        PiSocketManager.emit("get_state", JSONObject())
        syncAmsiSocketPreviewToggle()
    }

    private fun startPolling() {
        stopPolling()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (currentIp.isNotEmpty()) {
                        checkStatus(currentIp)


                        val now = System.currentTimeMillis()
                        if (now - lastEnvEventAt > envPollMs) {
                            pollEnvOnce()
                        }
                        if (now - lastCpuTempEventAt > cpuTempPollMs) {
                            pollCpuTempOnce()
                        }
                        if (System.currentTimeMillis() - lastBatteryEventAt > batteryPollMs) {
                            pollBatteryOnce()
                        }

                        // If nothing good for a while → schedule disconnect
                        val now2 = System.currentTimeMillis()
                        if (now2 - lastOkTimestamp > pollMs) {
                            scheduleDebouncedDisconnect()
                        }
                    } else {
                        updateConnUi(null)
                    }
                    delay(pollMs)
                }
            }
        }
    }


    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun checkStatus(ip: String) {
        val req = Request.Builder().url("http://$ip:5000/status").get().build()
        quickClient.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (!isAdded) return
                consecutiveStatusFailures++
                scheduleDebouncedDisconnect()
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                if (!isAdded) return
                consecutiveStatusFailures = 0
                lastOkTimestamp = System.currentTimeMillis()
                runOnViewThread { updateConnUi(true) }
                cancelPendingDisconnect()
            }
        })
    }
    private fun pollEnvOnce() {
        val ip = currentIp
        if (ip.isBlank()) return
        val req = Request.Builder().url("http://$ip:5000/env").get().build()
        quickClient.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Ignore quietly; socket is primary path
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val body = it.body?.string().orEmpty()
                    try {
                        val root = org.json.JSONObject(body)
                        val env = root.optJSONObject("env") ?: return
                        val t = env.optDouble("temp_c", Double.NaN)
                        val h = env.optDouble("humidity", Double.NaN)
                        val ts = env.optString("ts_utc", null)

                        latestTempC = if (t.isNaN()) null else t
                        latestHumidity = if (h.isNaN()) null else h
                        latestEnvIso = ts

                        if (!isAdded) return
                        runOnViewThread {
                            renderEnv(latestTempC, latestHumidity, latestEnvIso)
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        })
    }

    private fun pollCpuTempOnce() {
        val ip = currentIp
        if (ip.isBlank()) return
        val req = Request.Builder().url("http://$ip:5000/cpu").get().build()
        quickClient.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Ignore quietly; socket is primary path
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val body = it.body?.string().orEmpty()
                    try {
                        val root = org.json.JSONObject(body)
                        val cpu = root.optJSONObject("cpu") ?: root
                        val t = cpu.optDouble("temp_c", Double.NaN)
                        val ts = cpu.optString("ts_utc", null)

                        latestCpuTempC = if (t.isNaN()) null else t
                        latestCpuTempIso = ts
                        lastCpuTempEventAt = System.currentTimeMillis()

                        if (!isAdded) return
                        runOnViewThread {
                            renderTemperatureChip()
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        })
    }

    private fun scheduleDebouncedDisconnect() {
        if (disconnectJob != null) return
        disconnectJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(disconnectGraceMs)
            val now = System.currentTimeMillis()
            val stale = consecutiveStatusFailures >= 3 || (now - lastOkTimestamp) > 15_000
            if (!isAdded) return@launch
            if (stale) {
                updateConnUi(false)
            }
            disconnectJob = null
        }
    }

    private fun cancelPendingDisconnect() {
        disconnectJob?.cancel()
        disconnectJob = null
    }

    private fun forceDisconnect() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Step 1: ask Pi to hard-abort any running job (AMSI, CAL, PMFI, preview, LEDs...)
            // Safe to ignore failures: we're going to drop the socket no matter what.
            val ip = currentIp
            if (ip.isNotEmpty()) {
                try {
                    // You add this to PiApi: POST /abort?reason=user%20disconnect
                    val resp = PiApi.api.abortAll("user disconnect")
                    // we don't actually need to inspect resp here
                } catch (_: Exception) {
                    // swallow; maybe we're already offline
                }
            }

            // Step 2: local teardown
            stopPolling()
            cancelPendingDisconnect()
            try { PiSocketManager.disconnect() } catch (_: Exception) {}

            // clear local flags so UI unlocks immediately
            isPiConnected = false
            capabilitiesReady = false
            fanControlAvailable = false
            binding.switchFanOverride.visibility = View.GONE
            isConnecting = false
            isCaptureOngoing = false
            isPmfiRunning = false
            previewActive = false
            vm.resetToIdle()
            wasPreviewOnBeforeAmsi = false

            // reset all visible UI surfaces to "fresh"
            resetUiToFreshState()
            resetPmfiUi(hide = true)
            setUiBusy(false)

            // jump user back to start tab
            (activity as? MainActivity)?.goToStartPage()

            toast("Disconnected from MFi")
        }
    }


    private fun renderTopConnectionChip(connected: Boolean?) {
        val icon = when (connected) {
            true -> R.drawable.circle_green
            false -> R.drawable.circle_red
            else -> R.drawable.circle_grey
        }

        binding.topConnectionChip.setChipIconResource(icon)
        binding.topConnectionChip.text = when {
            connected == true -> "Connected"
            isConnecting -> "Connecting"
            connected == false -> "Disconnected"
            else -> "Unknown"
        }
    }


    private fun updateConnUi(connected: Boolean?) {
        val drawable = when (connected) {
            true -> R.drawable.circle_green
            false -> R.drawable.circle_red
            else -> R.drawable.circle_grey
        }
        binding.piConnectionDot.background =
            ContextCompat.getDrawable(requireContext(), drawable)
        renderTopConnectionChip(connected)
        binding.piConnectionStatus.text = when (connected) {
            true -> "Status: Connected"
            false -> {
                val host = currentIp.ifBlank { binding.ipAddressInput.text?.toString()?.trim().orEmpty() }
                if (host.isNotBlank()) {
                    "Status: Not Connected (${host}:5000 unreachable)"
                } else {
                    "Status: Not Connected"
                }
            }
            else -> if (isConnecting) "Status: Connecting" else "Status: Unknown"
        }
        if (connected == true) {
            isPiConnected = true
            isConnecting = false
            cancelPendingDisconnect()
        } else if (connected == false) {
            isPiConnected = false
            capabilitiesReady = false
            fanControlAvailable = false
            binding.switchFanOverride.visibility = View.GONE
            isConnecting = false
        }

        binding.controlsScrollview.setDeviceConnected(isPiConnected)

// use the single source of truth
        val busy = isGlobalBusy()
        setUiBusy(busy && isPiConnected)

        binding.ipInputLayout.helperText = when (connected) {
            false -> "Check the MF IP in your phone hotspot settings, then update it here."
            else -> "Use the MFi IP shown in your phone hotspot settings."
        }

    }

    // ===== Socket handlers (core + calibration) =====
    private fun hookSocketCore() {
        onSocketEvent("connect") {
            if (!isAdded) return@onSocketEvent
            runOnViewThread {
                updateConnUi(true)
                syncAmsiSocketPreviewToggle()
                fetchDeviceCapabilities()
            }
        }
        onSocketEvent("disconnect") { scheduleDebouncedDisconnect() }
        onSocketEvent("connect_error") { scheduleDebouncedDisconnect() }
        onSocketEvent("error") { scheduleDebouncedDisconnect() }
    }
    // ===== Environment (Temp / Humidity) socket + render =====
    // In ControlFragment (or wherever hookEnvSocket() lives)
    private fun hookEnvSocket() {
        // Pi sends either a JSONObject, a JSON string, or a Map
        onSocketEvent("env.update") { payload ->
            try {
                val obj: org.json.JSONObject? = when (payload) {
                    is org.json.JSONObject -> payload
                    is String -> runCatching { org.json.JSONObject(payload) }.getOrNull()
                    is Map<*, *> -> org.json.JSONObject(payload)
                    else -> null
                }

                if (obj == null) {
                    Log.w("ControlFragment", "env.update: unexpected payload type: ${payload?.javaClass?.name}")
                    return@onSocketEvent
                }

                // Support both: { env:{ temp_c, humidity, ts_utc } } and flat { temp_c, humidity, ts_utc }
                val env = obj.optJSONObject("env") ?: obj

                val tRaw = env.optDouble("temp_c", Double.NaN)
                val hRaw = env.optDouble("humidity", Double.NaN)
                val ts   = env.optString("ts_utc").takeIf { it.isNotBlank() }

                latestTempC = if (tRaw.isNaN()) null else tRaw
                latestHumidity = if (hRaw.isNaN()) null else hRaw
                latestEnvIso = ts
                lastEnvEventAt = System.currentTimeMillis()

                // If a run/session id is included, persist immediately
                val runId = sequenceOf("runId", "sessionId", "session_id")
                    .mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
                    .firstOrNull()

                if (runId != null && (latestTempC != null || latestHumidity != null || latestEnvIso != null)) {
                    viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val dao = com.example.msiandroidapp.data.AppDatabase.getDatabase(requireContext()).sessionDao()
                            val rows = dao.updateEnvByRunId(runId, latestTempC, latestHumidity, latestEnvIso)
                            Log.i("ControlFragment", "env.update saved for runId=$runId rows=$rows T=$latestTempC RH=$latestHumidity ts=$latestEnvIso")
                        }.onFailure { e ->
                            Log.w("ControlFragment", "env.update DB error: ${e.message}")
                        }
                    }
                }

                if (!isAdded) return@onSocketEvent
                runOnViewThread {
                    renderEnv(latestTempC, latestHumidity, latestEnvIso)
                }
            } catch (t: Throwable) {
                Log.e("ControlFragment", "Handler for 'env.update' failed", t)
            }
        }
    }

    private fun hookCpuTempSocket() {
        onSocketEvent("cpu.temp") { payload ->
            try {
                val obj: org.json.JSONObject? = when (payload) {
                    is org.json.JSONObject -> payload
                    is String -> runCatching { org.json.JSONObject(payload) }.getOrNull()
                    is Map<*, *> -> org.json.JSONObject(payload)
                    else -> null
                }

                if (obj == null) {
                    Log.w("ControlFragment", "cpu.temp: unexpected payload type: ${payload?.javaClass?.name}")
                    return@onSocketEvent
                }

                val cpu = obj.optJSONObject("cpu") ?: obj
                val tRaw = cpu.optDouble("temp_c", Double.NaN)
                latestCpuTempC = if (tRaw.isNaN()) null else tRaw
                latestCpuTempIso = cpu.optString("ts_utc").takeIf { it.isNotBlank() }
                lastCpuTempEventAt = System.currentTimeMillis()

                if (!isAdded) return@onSocketEvent
                runOnViewThread {
                    renderTemperatureChip()
                }
            } catch (t: Throwable) {
                Log.e("ControlFragment", "Handler for 'cpu.temp' failed", t)
            }
        }
    }



    // Pretty-print on the Control tab
    private fun renderEnv(tempC: Double?, rh: Double?, iso: String?) {
        val tLbl = if (tempC == null) "Temp: —" else String.format(Locale.UK, "Temp: %.1f \u00B0C", tempC)
        val hLbl = if (rh == null)    "RH: —"   else String.format(Locale.UK, "RH: %.0f %%", rh)

        renderTemperatureChip()
        binding.topHumChip.text  = hLbl

        // keep hidden legacy labels in sync
        binding.envTempText.text     = envTempLabel(tempC)
        binding.envHumidityText.text = hLbl
    }

    private fun envTempLabel(tempC: Double?): String =
        if (tempC == null) "Temp: —" else String.format(Locale.UK, "Temp: %.1f \u00B0C", tempC)

    private fun cpuTempLabel(tempC: Double?): String =
        if (tempC == null) "CPU: —" else String.format(Locale.UK, "CPU: %.1f \u00B0C", tempC)

    private fun renderTemperatureChip() {
        binding.topTempChip.text = if (showingCpuTemp) {
            cpuTempLabel(latestCpuTempC)
        } else {
            envTempLabel(latestTempC)
        }
    }


    // ===== Preview / state callbacks =====
    private fun onPreviewImage(data: JSONObject, bmp: Bitmap) {
        cancelPendingDisconnect()
        if (!isAdded) return

        runOnViewThread {
            val indexedAmsiFrame = amsiPreviewIndex(data) in 0 until deviceChannelCount
            if (indexedAmsiFrame && (amsiSocketPreviewEnabled || isCaptureOngoing || vm.isCapturing.value == true)) {
                addAmsiGridBitmap(data, bmp)
                return@runOnViewThread
            }

            when (mode) {
                PreviewMode.LIVE_FEED, PreviewMode.STARTING -> {
                    if (mode != PreviewMode.LIVE_FEED) {
                        startLivePreview()
                    }
                    liveImage?.setImageBitmap(bmp)
                }

                PreviewMode.AMSI_GRID -> {
                    addAmsiGridBitmap(data, bmp)
                }

                PreviewMode.OFF -> Unit
            }
        }
    }

    private fun onStateUpdate(data: JSONObject) {
        // ---- 1) Parse the snapshot (server is source of truth) ----
        val serverSw4   = data.optBoolean("sw4", false)
        var sw4         = serverSw4
        var busyNow     = data.optBoolean("busy", false)
        var calNow      = data.optBoolean("calibrating", false)
        val pmfiNow     = data.optBoolean("pmfi_running", false)
        val lastBtn     = data.optString("last_button", "")
        val statusTag   = data.optString("status_tag", "")
        val pmfiStage   = data.optString("pmfi_stage", "")
        val pmfiSection = data.optString("pmfi_section", "")

        if (!isAdded) return
        runOnViewThread {
            serverTransitionInProgress = data.optBoolean("transitioning", false)
            if (modeTransitionInProgress) {
                lastSw4FromServer = serverSw4
                setUiBusy(true)
                return@runOnViewThread
            }

            // ---- 2) Heartbeat + cosmetic labels ----
            updateConnUi(true)
            if (lastBtn.isNotBlank()) {
                binding.lastPiButtonText.text = "MFi Button Pressed: $lastBtn"
            }
            if (pmfiStage.isNotBlank()) binding.pmfiStageLabel.text = pmfiStage
            if (pmfiSection.isNotBlank()) binding.pmfiSectionLabel.text = "Current section: $pmfiSection"
            if (statusTag == "AMSI_UPLOAD" && (isCaptureOngoing || vm.isCapturing.value == true)) {
                setAmsiUploadingZip()
            }

            // ---- 3) Apply calibration cooldown mask (prevents stale 'calibrating=true') ----
            val pair = dropStaleBusyFlagsFromCal(busyNow, calNow, pmfiNow)
            busyNow = pair.first
            calNow  = pair.second

            // ---- 4) Handle Pi-initiated STARTS first (grab the lock immediately) ----
            // AMSI (SW2)
            if (lastBtn == "SW2" && !isCaptureOngoing && !isPmfiRunning && (vm.isCalibrating.value != true)) {
                isCaptureOngoing = true
                isCalibratingOngoing = false
                isPmfiRunning = false
                setUiBusy(true)
                // Pi started AMSI; remember whether preview was on so we can restore after
                wasPreviewOnBeforeAmsi = lastSw4FromServer || binding.switchCameraPreview.isChecked || previewActive
                ensurePreviewOffAsync(1500)
                val wantsAmsiGrid = amsiSocketPreviewEnabled || binding.switchAmsiSocketPreview.isChecked
                if (wantsAmsiGrid) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        ensureAmsiSocketPreviewEnabledForCapture()
                    }
                    startImageGrid()
                } else {
                    clearPreview()
                }
                startCaptureUi()
                vm.prepareCapture(deviceChannelCount)
                vm.imageCount.value = 0
                vm.isCapturing.value = true
            }

            // CAL (SW3)
            if (lastBtn == "SW3" && !isCaptureOngoing && !isPmfiRunning && !isCalibratingOngoing && (vm.isCalibrating.value != true)) {
                calStartGraceUntil = now() + 1200L
                busyNow = true
                calNow = true
                isCalibratingOngoing = true
                isCaptureOngoing = false
                isPmfiRunning = false
                setUiBusy(true)
                ensurePreviewOffAsync(1500)
                resetCalExpectedImages()
            vm.startCalibration(totalChannels = deviceChannelCount)
                showCalUi(true)
            }

            // PMFI (server says running even if started elsewhere)
            if (pmfiNow && !isPmfiRunning && !isCaptureOngoing && (vm.isCalibrating.value != true)) {
                isPmfiRunning = true
                isCalibratingOngoing = false
                isCaptureOngoing = false
                setUiBusy(true)
                ensurePreviewOffAsync(1500)
                resetPmfiUi(hide = false)
                showPmfiUi()
                binding.pmfiStartBtn.text = "PMFI running…"
            }

            // CAL reported running (no SW3 in last_button)
            if (calNow && !isCalibratingOngoing && !isCaptureOngoing && !isPmfiRunning) {
                isCalibratingOngoing = true
                setUiBusy(true)
                if (sw4) ensurePreviewOffAsync(1500) // server still had preview on → ask it to stop
                clearPreviewSwitchAndCanvas()
                resetCalExpectedImages()
                vm.startCalibration(totalChannels = deviceChannelCount)
                showCalUi(true)
            }

            // ---- 5) Preview switch & canvas sync (server is authoritative) ----
            val nowMs = System.currentTimeMillis()
            val pendingState = previewRequestedState
            val pendingActive = pendingState != null && nowMs < previewRequestPendingUntil
            if (pendingState != null) {
                if (serverSw4 == pendingState) {
                    clearPreviewRequest()
                } else if (pendingActive) {
                    sw4 = pendingState
                }
            }

            lastSw4FromServer = serverSw4
            previewActive = sw4

            val localBusy =
                (isCaptureOngoing || isCalibratingOngoing || isPmfiRunning || (vm.isCalibrating.value == true)) ||
                        pmfiNow || calNow || busyNow
// If we’re idle and we wanted warming back, try once more immediately
            if (!localBusy && wasPreviewOnBeforeAmsi && !lastSw4FromServer && !resumePreviewPending && !pendingActive) {
                kickPreviewResume(maxMs = 2500L)
            }

            // Mirror the switch without causing feedback
            binding.switchCameraPreview.setOnCheckedChangeListener(null)
            binding.switchCameraPreview.isEnabled = isPiConnected && !localBusy
            binding.switchCameraPreview.alpha = if (binding.switchCameraPreview.isEnabled) 1f else 0.4f
            binding.switchCameraPreview.isChecked = sw4
            attachPreviewToggleListener()

            if (!localBusy) {
                // Idle → we are allowed to touch the canvas
                when {
                    sw4 && mode != PreviewMode.LIVE_FEED -> startLivePreview()
                    !sw4 && mode == PreviewMode.LIVE_FEED -> clearPreview()
                }
            } else {
                // Busy → ensure preview is OFF on Pi; clear only live canvas (preserve AMSI grid)
                previewActive = false
                clearLiveOnly()
            }

            // ---- 6) Failsafe: if everything is idle, drop any lingering UI flags ----
            if (now() < calStartGraceUntil) {
                return@runOnViewThread
            }
            if (!busyNow && !pmfiNow && !calNow) {
                // Finish cal UI if it was showing
                if (binding.calProgressBar.visibility == View.VISIBLE || binding.calProgressText.visibility == View.VISIBLE || isCalibratingOngoing || (vm.isCalibrating.value == true)) {
                    endCalibrationUi(null)
                }
                // If AMSI flags were left on but uploads are done, unlock
                if (isCaptureOngoing && (vm.imageCount.value ?: 0) >= amsiCaptureTotal) {
                    isCaptureOngoing = false
                }
            }

            // ---- 7) Final busy compute and UI interlock ----
            val uiBusy =
                (isCaptureOngoing || isCalibratingOngoing || isPmfiRunning || (vm.isCalibrating.value == true)) ||
                        busyNow || calNow || pmfiNow
            setUiBusy(uiBusy)
        }
    }
    private fun shouldRestorePreviewAfterJobs(): Boolean {
        // Only if we captured that preview was on earlier,
        // we’re connected, and nothing else is busy now.
        return wasPreviewOnBeforeAmsi && isPiConnected && !isGlobalBusy()
    }

    private fun restorePreviewIfNeeded() {
        if (!shouldRestorePreviewAfterJobs()) return
        // Don’t fight the user if they manually switched it off meanwhile
        viewLifecycleOwner.lifecycleScope.launch {
            // Ask Pi to set preview ON and wait for ack; ignore if it times out
            runCatching { ensurePreviewSet(true, timeoutMs = 2_000) }
            wasPreviewOnBeforeAmsi = false
        }
    }


    override fun onAttach(baseContext: Context) {
        // create a context with fontScale forced to 1.0
        val config = android.content.res.Configuration(baseContext.resources.configuration)
        config.fontScale = 1.0f

        val scaledContext = baseContext.createConfigurationContext(config)
        super.onAttach(scaledContext)
    }

    private fun clearPreviewSwitchAndCanvas() {
        // Reflect OFF in the switch without re-triggering the listener
        binding.switchCameraPreview.setOnCheckedChangeListener(null)
        binding.switchCameraPreview.isChecked = false
        attachPreviewToggleListener()

        // Clear state + canvas
        clearPreviewRequest()
        previewActive = false
        clearPreview()
    }

    private fun clearLiveOnly() {
        if (mode == PreviewMode.LIVE_FEED || mode == PreviewMode.STARTING) {
            showPreviewOffState()
        }
    }

    private fun applyDeviceCapabilities(payload: JSONObject) {
        capabilitiesReady = true
        modeHandshakeVersion = ModeHandshake.updateVersion(
            modeHandshakeVersion,
            if (payload.has("mode_handshake_version") && !payload.isNull("mode_handshake_version"))
                payload.optInt("mode_handshake_version", 0) else null
        )
        val count = payload.optInt("channel_count", 16).coerceAtLeast(1)
        deviceChannelCount = count
        val channelArray = payload.optJSONArray("channels")
        val channelsByIndex = (0 until (channelArray?.length() ?: 0)).mapNotNull {
            channelArray?.optJSONObject(it)
        }.associateBy { it.optInt("index", -1) }
        channelWavelengths = List(count) { index ->
            channelsByIndex[index]?.optInt("wavelength_nm", 0)?.takeIf { it > 0 }
        }

        val preferences = requireContext().getSharedPreferences("device_capabilities", Context.MODE_PRIVATE)
        val saved = preferences.getString("amsi_channels_${currentIp}_$deviceChannelCount", null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in 0 until count }
            ?.toSet()
            .orEmpty()
        selectedAmsiChannels = saved.ifEmpty { (0 until count).toSet() }
        ensureAmsiGridCapacity(count)
        calTotalChannels = count
        calExpectedImages = count
        binding.buttonStartAmsi.contentDescription =
            "Start AMSI; ${selectedAmsiChannels.size} of $count channels selected"
        val model = payload.optString("device_model", "")
        fanControlAvailable = payload.optBoolean("fan_control", false) ||
            model.equals("MFI-3", ignoreCase = true) || count == 23
        binding.switchFanOverride.visibility = if (fanControlAvailable) View.VISIBLE else View.GONE
        if (!fanControlAvailable) renderFanState(false)
        setUiBusy(isGlobalBusy())
    }

    private fun fetchDeviceCapabilities() {
        if (currentIp.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { PiApi.api.capabilities() }.getOrNull()?.body()?.let { capabilities ->
                val payload = JSONObject().apply {
                    put("protocol_version", capabilities.protocol_version)
                    put("mode_handshake_version", capabilities.mode_handshake_version)
                    put("device_model", capabilities.device_model)
                    put("channel_count", capabilities.channel_count)
                    put("fan_control", capabilities.fan_control)
                    put("channels", org.json.JSONArray().apply {
                        capabilities.channels.forEach { channel ->
                            put(JSONObject().apply {
                                put("index", channel.index)
                                put("wavelength_nm", channel.wavelength_nm ?: JSONObject.NULL)
                            })
                        }
                    })
                }
                applyDeviceCapabilities(payload)
            }
            // Probe independently so an older/incomplete capabilities response
            // cannot hide fan control on an MFI-3 server.
            probeFanSupport()
        }
    }

    private fun probeFanSupport() {
        viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching { PiApi.api.fanState() }.getOrNull()
            val state = response?.takeIf { it.isSuccessful }?.body()
            if (state?.available == true) {
                fanControlAvailable = true
                binding.switchFanOverride.visibility = View.VISIBLE
                renderFanState(state.force_on)
                setUiBusy(isGlobalBusy())
            }
        }
    }

    private fun refreshFanState() = probeFanSupport()

    private fun renderFanState(forceOn: Boolean) {
        binding.switchFanOverride.setOnCheckedChangeListener(null)
        binding.switchFanOverride.isChecked = forceOn
        binding.switchFanOverride.text = if (forceOn) "Fan: Maximum" else "Fan: Auto (always on)"
        binding.switchFanOverride.setOnCheckedChangeListener { _, checked ->
            if (fanControlAvailable && !fanRequestInFlight) setFanOverride(checked)
        }
    }

    private fun setFanOverride(forceOn: Boolean) {
        fanRequestInFlight = true
        binding.switchFanOverride.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = PiApi.api.setFanMode(FanControlBody(forceOn))
                if (!response.isSuccessful) throw IOException(response.body()?.error ?: "HTTP ${response.code()}")
                renderFanState(response.body()?.force_on ?: forceOn)
            } catch (e: Exception) {
                toast("Fan control failed: ${e.localizedMessage}")
                refreshFanState()
            } finally {
                fanRequestInFlight = false
                binding.switchFanOverride.isEnabled = fanControlAvailable && isPiConnected
            }
        }
    }

    private fun ensureAmsiGridCapacity(count: Int) {
        binding.amsiPreviewGrid.removeAllViews()
        gridImages.clear()
        // Set capacity before adding tiles, including MFi-3's channels 16?22.
        binding.amsiPreviewGrid.rowCount = (count + 3) / 4
        binding.amsiPreviewGrid.columnCount = minOf(4, count)
        repeat(count) { index ->
            val image = ImageView(requireContext()).apply {
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.ic_menu_gallery)
                alpha = 0.45f
            }
            val label = TextView(requireContext()).apply {
                text = channelWavelengths.getOrNull(index)?.let { "$it nm" } ?: "Ch ${index + 1}"
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(150, 0, 0, 0))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }
            val tile = FrameLayout(requireContext()).apply {
                setBackgroundColor(Color.BLACK)
                addView(image, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                addView(label)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = (74 * resources.displayMetrics.density).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
            }
            binding.amsiPreviewGrid.addView(tile)
            gridImages.add(image)
        }
        binding.amsiPreviewGrid.rowCount = (count + 3) / 4
        binding.amsiPreviewGrid.columnCount = minOf(4, count)
        binding.amsiGridProgressBar.max = count
        binding.amsiGridTitleText.text = "AMSI Preview"
        // Capabilities may be refreshed during a scan; retain received images.
        if (mode == PreviewMode.AMSI_GRID || vm.capturedBitmaps.value.orEmpty().any { it != null }) {
            updateGrid(vm.capturedBitmaps.value.orEmpty())
        }
    }

    private fun showAmsiChannelDialog(onConfirmed: () -> Unit) {
        val labels = Array(deviceChannelCount) { index ->
            channelWavelengths.getOrNull(index)?.let { "Channel ${index + 1} ($it nm)" }
                ?: "Channel ${index + 1}"
        }
        val checked = BooleanArray(deviceChannelCount) { it in selectedAmsiChannels }
        AlertDialog.Builder(requireContext())
            .setTitle("AMSI capture channels")
            .setMultiChoiceItems(labels, checked) { _, which, enabled -> checked[which] = enabled }
            .setNeutralButton("All") { _, _ ->
                selectedAmsiChannels = (0 until deviceChannelCount).toSet()
                saveAmsiChannelSelection()
                onConfirmed()
            }
            .setPositiveButton("Save") { _, _ ->
                val selected = checked.indices.filter { checked[it] }.toSet()
                if (selected.isEmpty()) {
                    toast("Select at least one channel")
                    return@setPositiveButton
                }
                selectedAmsiChannels = selected
                saveAmsiChannelSelection()
                onConfirmed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAmsiChannelSelection() {
        binding.buttonAmsiChannels.contentDescription =
            "Choose AMSI channels; ${selectedAmsiChannels.size} of $deviceChannelCount selected"
        requireContext().getSharedPreferences("device_capabilities", Context.MODE_PRIVATE)
            .edit()
            .putString("amsi_channels_${currentIp}_$deviceChannelCount", selectedAmsiChannels.sorted().joinToString(","))
            .apply()
    }

    // ===== Actions =====
    private fun triggerButton(buttonId: String) {
        if (currentIp.isEmpty()) { toast("Set IP address first"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (buttonId) {
                    "SW4" -> ensurePreviewSet(false)
                    "SW3" -> performModeTransition("calibration")
                    "SW2" -> performModeTransition("amsi", channels = selectedAmsiChannels.sorted())
                    else -> error("Unknown acquisition mode")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Mode change failed: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun readUriText(uri: Uri): String = withContext(Dispatchers.IO) {
        requireContext().contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use {
            it?.readText().orEmpty()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }

    private fun sanitizeIniFilename(name: String?): String {
        val raw = name?.trim().orEmpty().ifBlank { "pmfi_upload.ini" }
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val lower = cleaned.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".ini") || lower.endsWith(".txt") -> cleaned
            else -> "$cleaned.ini"
        }
    }

    private fun uploadPmfiIniText(iniText: String, preferredName: String?) {
        if (currentIp.isEmpty()) { toast("Set IP address first"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val iniFile = withContext(Dispatchers.IO) {
                    val safeName = sanitizeIniFilename(preferredName)
                    File(requireContext().cacheDir, safeName).apply { writeText(iniText) }
                }
                val resp = PiApi.api.iniUpload(PiApi.makeIniPart(iniFile))
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val name = body?.name ?: iniFile.name
                    toast("INI uploaded: $name")
                } else {
                    val errBody = resp.errorBody()?.string().orEmpty()
                    toast("INI upload failed: ${resp.code()} $errBody")
                }
            } catch (e: Exception) {
                toast("Network error: ${e.localizedMessage}")
            }
        }
    }

    private fun startPmfi(iniText: String) {
        val sessionId = UUID.randomUUID().toString()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                resetPmfiUi(hide = false)
                showPmfiUi()
                performModeTransition("pmfi", iniText = iniText, sessionId = sessionId)
                isPmfiRunning = true
                setUiBusy(true)
                binding.pmfiStartBtn.text = "PMFI running?"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("PMFI could not start: ${e.localizedMessage}")
                isPmfiRunning = false
                setUiBusy(false)
                setPmfiButtonBusy(false)
                binding.pmfiStartBtn.text = "Start PMFI"
            }
        }
    }

    private fun sendShutdown() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = PiApi.api.shutdownSystem()
                if (resp.isSuccessful) toast("Shutdown command sent")
                else toast("Failed: ${resp.code()}")
            } catch (e: Exception) {
                toast("Network error: ${e.localizedMessage}")
            }
        }
    }

    private fun sendFactoryReset() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = PiApi.api.factoryReset()
                if (resp.isSuccessful) toast("Factory reset command sent")
                else toast("Failed: ${resp.code()}")
            } catch (e: Exception) {
                toast("Network error: ${e.localizedMessage}")
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.getDefault(), "%.1f MB", mb)
        } else {
            String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        }
    }

    private fun showCalUi(show: Boolean) {
        if (show) {
            refreshCalExpectedImages()
            binding.calProgressBar.setDeterminateProgress(0, calExpectedImages)
            binding.calProgressBar.visibility = View.VISIBLE
            binding.calProgressText.visibility = View.VISIBLE
            updateCalProgressText()
        } else {
            if (binding.calProgressBar.isIndeterminate) binding.calProgressBar.isIndeterminate = false
            binding.calProgressBar.progress = 0
            binding.calProgressBar.visibility = View.GONE
            binding.calProgressText.text = ""
            binding.calProgressText.visibility = View.GONE
        }
    }

    private fun resetCalExpectedImages() {
        calDarkFrameSeen = false
        calExtraImagesExpected = 0
        calDarkImagesUploaded = 0
        calUploadedImages = 0
        calUploadTotalImages = deviceChannelCount
        calInfoLine = ""
        calStageLine = ""
        calTotalChannels = vm.calTotalChannels.value ?: 16
        calExpectedImages = deviceChannelCount
        refreshCalExpectedImages()
    }

    private fun refreshCalExpectedImages() {
        val totalChannels = vm.calTotalChannels.value ?: calTotalChannels
        calExpectedImages = totalChannels.coerceAtLeast(1)
        if (binding.calProgressBar.isIndeterminate) binding.calProgressBar.isIndeterminate = false
        binding.calProgressBar.max = calExpectedImages
        if (binding.calProgressBar.progress > calExpectedImages) {
            binding.calProgressBar.progress = calExpectedImages
        }
    }

    private fun updateCalProgressText(serverPercent: Int? = null) {
        val done = binding.calProgressBar.progress
        val total = binding.calProgressBar.max
        if (calStageLine.equals("Uploading calibration images", ignoreCase = true)) {
            binding.calProgressText.text = "Uploading calibration images..."
            return
        }
        if (calStageLine.equals("Packing calibration ZIP", ignoreCase = true) ||
            calStageLine.equals("Uploading calibration ZIP", ignoreCase = true) ||
            calStageLine.equals("Uploading calibration metadata", ignoreCase = true)
        ) {
            binding.calProgressText.text = "$calStageLine..."
            return
        }
        if (
            calStageLine.equals("Calibration ZIP uploaded", ignoreCase = true) ||
            calStageLine.equals("Calibration ZIP upload failed", ignoreCase = true)
        ) {
            binding.calProgressText.text = "$calStageLine: $done/$total (${percentText(done, total, serverPercent)})"
            return
        }
        val stage = if (calStageLine.isNotBlank()) " - $calStageLine" else ""
        val info = if (calInfoLine.isNotBlank()) " - $calInfoLine" else ""
        binding.calProgressText.text = "Calibration: $done/$total (${percentText(done, total, serverPercent)})$stage$info"
    }
    // SUSPEND: turn preview off on the Pi and locally, wait briefly for ack.
    private suspend fun ensurePreviewOff(timeoutMs: Long = 2_000L) {
        killPreviewIfNeededAndWait(timeoutMs)
    }


    // NON-SUSPEND wrapper: call from non-suspend contexts (e.g. socket callback)
    private fun ensurePreviewOffAsync(timeoutMs: Long = 2_000L) {
        if (modeTransitionInProgress || serverTransitionInProgress) return
        viewLifecycleOwner.lifecycleScope.launch { ensurePreviewOff(timeoutMs) }
    }



    // Master UI interlock.
// Call this any time connection/busy states change.
// Rules:
//  - While global busy: ONLY Disconnect is interactive.
//  - When idle but connected: normal buttons allowed.
//  - When disconnected: only IP connect controls are allowed.
    private fun setUiBusy(_ignored: Boolean) {
        val connected   = isPiConnected
        val connecting  = isConnecting
        val globalBusy  = isGlobalBusy()
        if (!globalBusy) {
            binding.buttonStartAmsi.isEnabled = connected
            binding.buttonStartAmsi.alpha     = if (connected) 1f else 0.4f

            binding.buttonCalibrate.isEnabled = connected
            binding.buttonCalibrate.alpha     = if (connected) 1f else 0.4f

            binding.pmfiStartBtn.isEnabled    = connected
            binding.pmfiStartBtn.isClickable  = connected
            binding.pmfiStartBtn.alpha        = if (connected) 1f else 0.4f

            val canTogglePreview = connected
            binding.switchCameraPreview.isEnabled = canTogglePreview
            binding.switchCameraPreview.alpha     = if (canTogglePreview) 1f else 0.4f

            val canToggleAmsiSocketPreview = connected && !amsiSocketPreviewToggleInFlight
            binding.switchAmsiSocketPreview.isEnabled = canToggleAmsiSocketPreview
            binding.switchAmsiSocketPreview.alpha = if (canToggleAmsiSocketPreview) 1f else 0.4f
        }
        // --- Disconnect button ---
        // Always allowed if we're in any state except "we literally have no connection at all and haven't even tried".
        // i.e. user can always bail out if there's an active link or a link being established.
        binding.buttonDisconnect.isEnabled = connected || connecting
        binding.buttonDisconnect.alpha = if (binding.buttonDisconnect.isEnabled) 1f else 0.5f

        // --- Connection widgets (IP box + Set IP button) ---
        // Once we're connected, we never allow the IP to change until you disconnect.
        // Also blocked while we're in the middle of connecting.
        val canEditConnectionFields = !connected && !connecting && !globalBusy
        binding.setIpButton.isEnabled    = canEditConnectionFields
        binding.ipAddressInput.isEnabled = canEditConnectionFields

        // --- Camera preview switch ---
        // You cannot toggle preview while globalBusy.
        // Also can't toggle if you're not connected.
        val canTogglePreview = connected && !globalBusy
        binding.switchCameraPreview.isEnabled = canTogglePreview
        // If it's disabled while checked, leave it checked visually but grey it out.
        binding.switchCameraPreview.alpha = if (canTogglePreview) 1f else 0.4f

        // --- AMSI Socket.IO image grid switch ---
        // This controls whether the Pi emits per-channel preview images during AMSI.
        val canToggleAmsiSocketPreview = connected && !globalBusy && !amsiSocketPreviewToggleInFlight
        binding.switchAmsiSocketPreview.isEnabled = canToggleAmsiSocketPreview
        binding.switchAmsiSocketPreview.alpha = if (canToggleAmsiSocketPreview) 1f else 0.4f

        binding.buttonDiagnostics.isEnabled = connected && !globalBusy
        binding.buttonAmsiChannels.isEnabled = connected && capabilitiesReady && !globalBusy
        binding.switchFanOverride.isEnabled = fanControlAvailable && connected && !fanRequestInFlight
        binding.switchFanOverride.alpha = if (binding.switchFanOverride.isEnabled) 1f else 0.4f

        // --- AMSI capture button (SW2 trigger) ---
        // Only if connected AND idle.
        val canStartAmsi = connected && !globalBusy
        binding.buttonStartAmsi.isEnabled = canStartAmsi
        binding.buttonStartAmsi.alpha = if (canStartAmsi) 1f else 0.4f

        // --- Calibration button (SW3 trigger) ---
        val canStartCal = connected && !globalBusy
        binding.buttonCalibrate.isEnabled = canStartCal
        binding.buttonCalibrate.alpha = if (canStartCal) 1f else 0.4f

        // --- PMFI start button ---
        // Only if connected AND idle.
        val canStartPmfi = connected && !globalBusy
        binding.pmfiStartBtn.isEnabled   = canStartPmfi
        binding.pmfiStartBtn.isClickable = canStartPmfi
        binding.pmfiStartBtn.alpha       = if (canStartPmfi) 1f else 0.4f
        binding.pmfiStartBtn.text =
            if (isPmfiRunning) "PMFI running…" else "Start PMFI"

        // PMFI INI editor:
        // Lock editing while PMFI is actively running so you can't silently change the text mid-run
        // (prevents confusion about "what config did I send?")
        binding.pmfiIniEdit.isEnabled = connected && !isPmfiRunning

        // --- Shutdown button ---
        // While globalBusy: we DO NOT allow shutdown from the app (you should abort via Disconnect first).
        // After that, user can reconnect and shut down cleanly when idle.
        val canShutdown = connected && !globalBusy
        binding.buttonShutdown.isEnabled = canShutdown
        binding.buttonShutdown.alpha     = if (canShutdown) 1f else 0.4f

        val canFactoryReset = connected && !globalBusy
        binding.buttonFactoryReset.isEnabled = canFactoryReset
        binding.buttonFactoryReset.alpha     = if (canFactoryReset) 1f else 0.4f
    }



    private fun setPmfiButtonBusy(busy: Boolean) {
        // Busy = PMFI running → button disabled + greyed + label changes
        val enabled = !busy && isPiConnected && !isCaptureOngoing && vm.isCalibrating.value != true
        binding.pmfiStartBtn.isEnabled = enabled
        binding.pmfiStartBtn.isClickable = enabled
        binding.pmfiStartBtn.alpha = if (enabled) 1f else 0.5f
        binding.pmfiStartBtn.text = if (busy) "PMFI running…" else "Start PMFI"
    }


    private fun saveIp(ip: String) {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
        prefs.edit().putString("server_ip", ip.trim()).apply()
    }

    private fun restoreSavedIp() {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
        val saved = prefs.getString("server_ip", "")?.trim().orEmpty()
        if (saved.isNotEmpty()) {
            binding.ipAddressInput.setText(saved)
            setBaseUrls(saved)
        }
    }
    // Try to reserve the global busy lock for a specific mode.
// mode = "amsi", "cal", "pmfi"
// returns true if we successfully became busy, false if someone else is already busy.
    private fun tryBeginBusy(mode: String): Boolean {
        if (isGlobalBusy()) return false

        when (mode) {
            "amsi" -> {
                isCaptureOngoing = true
                isCalibratingOngoing = false
                isPmfiRunning = false
            }
            "cal" -> {
                isCaptureOngoing = false
                isCalibratingOngoing = true
                isPmfiRunning = false
            }
            "pmfi" -> {
                isCaptureOngoing = false
                isCalibratingOngoing = false
                isPmfiRunning = true
            }
        }

        // lock whole UI right now
        setUiBusy(true)
        return true
    }

}
