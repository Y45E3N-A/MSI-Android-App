package com.example.msiandroidapp.ui.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
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
import com.example.msiandroidapp.network.PiSocketManager
import com.example.msiandroidapp.util.UploadProgressBus
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.example.msiandroidapp.network.PmfiStartBody

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

    // ===== View binding (lifecycle-safe) =====
    private var _binding: FragmentControlBinding? = null
    private val binding: FragmentControlBinding
        get() = _binding ?: error("Binding is only valid between onCreateView and onDestroyView")

    // ViewModel (assumes you already have this)
    private val vm: ControlViewModel by activityViewModels()

    // Connection / state
    private var currentIp = ""
    private var isPiConnected = false
    private var isConnecting = false
    private var isCaptureOngoing = false
    private var isPmfiRunning = false

    // Preview UI
    private enum class PreviewMode { NONE, IMAGE_CAPTURE, LIVE_FEED }
    private var mode: PreviewMode = PreviewMode.NONE
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewScroll: ScrollView
    private var liveImage: ImageView? = null
    private var grid: GridLayout? = null
    private val gridImages = ArrayList<ImageView>(16)

    // Polling / watchdog
    private var pollJob: Job? = null
    private var disconnectJob: Job? = null
    private var lastOkTimestamp = 0L
    private var consecutiveStatusFailures = 0

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewContainer = view.findViewById(R.id.preview_container)
        previewScroll = view.findViewById(R.id.preview_scrollview)

        setupButtons()
        observeUploadProgress()
        hookSocketCore()
        hookCalibrationSocket()
        hookPmfiSocket()

        restoreSavedIp()
        updateConnUi(null)
        setUiBusy(false)

        if (currentIp.isNotEmpty()) {
            PiSocketManager.setBaseUrl(currentIp)
            PiSocketManager.connect(::onPreviewImage, ::onStateUpdate)
            PiSocketManager.emit("get_state", JSONObject())
        }

        // Restore capture UI if mid-session
        val count = vm.imageCount.value ?: 0
        val imgs = vm.capturedBitmaps.value ?: List(16) { null }
        if (count in 1..16 || imgs.any { it != null }) {
            startImageGrid()
            updateGrid(imgs)
            binding.captureProgressBar.max = 16
            binding.captureProgressBar.progress = count
            binding.captureProgressText.text = "Receiving images: $count/16"
            binding.captureProgressBar.visibility = View.VISIBLE
            binding.captureProgressText.visibility = View.VISIBLE
        }

        // Observers (all bound to viewLifecycleOwner)
        vm.capturedBitmaps.observe(viewLifecycleOwner) {
            updateGrid(it ?: List(16) { null })
        }
        vm.imageCount.observe(viewLifecycleOwner) { c ->
            binding.captureProgressBar.progress = c
            binding.captureProgressText.text = "Receiving images: $c/16"
            when {
                c in 1..15 -> {
                    binding.captureProgressBar.visibility = View.VISIBLE
                    binding.captureProgressText.visibility = View.VISIBLE
                }
                c == 16 -> {
                    binding.captureProgressText.text = "All images received!"
                }
                else -> {
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                }
            }
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
        _binding = null
        super.onDestroyView()
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
            connectSocket()
            checkStatus(ip)
        }

        // Disconnect
        binding.buttonDisconnect.setOnClickListener {
            if (isPmfiRunning) {
                AlertDialog.Builder(requireContext())
                    .setTitle("PMFI running")
                    .setMessage("Disconnecting may interrupt uploads. Disconnect anyway?")
                    .setPositiveButton("Disconnect") { _, _ -> forceDisconnect() }
                    .setNegativeButton("Cancel", null).show()
            } else forceDisconnect()
        }

        // Preview toggle (SW4)
        binding.switchCameraPreview.setOnCheckedChangeListener { _, checked ->
            if (!isPiConnected || isCaptureOngoing || isPmfiRunning || vm.isCalibrating.value == true) {
                binding.switchCameraPreview.isChecked = false
                return@setOnCheckedChangeListener
            }
            triggerButton("SW4")
            if (checked) startLivePreview() else clearPreview()
        }

        // AMSI (SW2)
        binding.buttonStartAmsi.setOnClickListener {
            if (!isPiConnected || isCaptureOngoing || isPmfiRunning || vm.isCalibrating.value == true) {
                toast("Pi not connected or busy"); return@setOnClickListener
            }
            if (binding.switchCameraPreview.isChecked) triggerButton("SW4") // ensure preview off
            startImageGrid()
            startCaptureUi()
            isCaptureOngoing = true
            setUiBusy(true)
            vm.capturedBitmaps.value = MutableList(16) { null }
            vm.imageCount.value = 0
            vm.isCapturing.value = true
            triggerButton("SW2")
        }

        // Calibration (SW3)
        binding.buttonCalibrate.setOnClickListener {
            if (!isPiConnected || isCaptureOngoing || isPmfiRunning || vm.isCalibrating.value == true) {
                toast("Busy or not connected"); return@setOnClickListener
            }
            triggerButton("SW3")
            vm.startCalibration(totalChannels = 16)
            showCalUi(true)
            setUiBusy(true)
        }

        // PMFI start
        binding.pmfiStartBtn.setOnClickListener {
            if (!isPiConnected || isCaptureOngoing || vm.isCalibrating.value == true) {
                toast("Busy or not connected"); return@setOnClickListener
            }
            val iniText = binding.pmfiIniEdit.text?.toString()?.trim().orEmpty()
            if (iniText.isBlank()) { toast("Paste an INI first"); return@setOnClickListener }
            if (binding.switchCameraPreview.isChecked) triggerButton("SW4")
            startPmfi(iniText)
        }

        // Shutdown
        binding.buttonShutdown.setOnClickListener {
            if (currentIp.isEmpty()) { toast("Set IP address first"); return@setOnClickListener }
            if (isPmfiRunning) { toast("PMFI running — wait for completion"); return@setOnClickListener }
            AlertDialog.Builder(requireContext())
                .setTitle("Shutdown System")
                .setMessage("Are you sure you want to shut down the Pi and instrument?")
                .setPositiveButton("Shutdown") { _, _ -> sendShutdown() }
                .setNegativeButton("Cancel", null).show()
        }

        // Initial progress widgets
        binding.captureProgressBar.max = 16
        binding.captureProgressBar.progress = 0
        binding.captureProgressBar.visibility = View.GONE
        binding.captureProgressText.visibility = View.GONE
    }

    private fun observeUploadProgress() {
        UploadProgressBus.uploadProgress.observe(viewLifecycleOwner) { (sessionId, count) ->
            Log.d(TAG, "Upload progress $sessionId : $count")
            vm.imageCount.value = count
            binding.captureProgressBar.progress = count
            binding.captureProgressText.text = "Receiving images: $count/16"

            if (count == 16) {
                vm.isCapturing.value = false
                binding.captureProgressText.text = "All images received!"
                // let UI linger briefly; Gallery will pick up from DB
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1500)
                    if (!isAdded) return@launch
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                    clearPreview()
                    isCaptureOngoing = false
                    setUiBusy(false)
                }
            } else if (count in 1..15) {
                vm.isCapturing.value = true
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
            }
        }
    }

    // ===== Status polling / connection =====
    private fun setBaseUrls(ip: String) {
        currentIp = ip
        PiApi.setBaseUrl(ip)
        PiSocketManager.setBaseUrl(ip)
        PiSocketManager.reconnect()
        PiSocketManager.emit("get_state", JSONObject())
    }

    private fun connectSocket() {
        PiSocketManager.connect(::onPreviewImage, ::onStateUpdate)
        PiSocketManager.emit("get_state", JSONObject())
    }

    private fun startPolling() {
        stopPolling()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (currentIp.isNotEmpty()) {
                        checkStatus(currentIp)
                        // If nothing good for a while → schedule disconnect
                        val now = System.currentTimeMillis()
                        if (now - lastOkTimestamp > pollMs) {
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
                requireActivity().runOnUiThread { updateConnUi(true) }
                cancelPendingDisconnect()
            }
        })
    }

    private fun scheduleDebouncedDisconnect() {
        if (disconnectJob != null) return
        disconnectJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(disconnectGraceMs)
            val stale = consecutiveStatusFailures >= 1 ||
                    (System.currentTimeMillis() - lastOkTimestamp) > disconnectGraceMs
            if (!isAdded) return@launch
            if (stale) {
                updateConnUi(false)
                clearPreview()
                isCaptureOngoing = false
                isPmfiRunning = false
                vm.isCapturing.value = false
                setUiBusy(false)
            }
            disconnectJob = null
        }
    }

    private fun cancelPendingDisconnect() {
        disconnectJob?.cancel()
        disconnectJob = null
    }

    private fun forceDisconnect() {
        stopPolling()
        cancelPendingDisconnect()
        try { PiSocketManager.disconnect() } catch (_: Exception) {}
        updateConnUi(false)
        isPiConnected = false
        isConnecting = false
        binding.pmfiStatusText.text = ""
        clearPreview()
        vm.isCapturing.value = false
        vm.imageCount.value = 0
        setUiBusy(false)
        toast("Disconnected from Pi")
    }

    private fun updateConnUi(connected: Boolean?) {
        val drawable = when (connected) {
            true -> R.drawable.circle_green
            false -> R.drawable.circle_red
            else -> R.drawable.circle_grey
        }
        binding.piConnectionDot.background =
            ContextCompat.getDrawable(requireContext(), drawable)
        binding.piConnectionStatus.text = when (connected) {
            true -> "Status: Connected"
            false -> "Status: Not Connected"
            else -> "Status: Unknown"
        }
        if (connected == true) {
            isPiConnected = true
            isConnecting = false
            cancelPendingDisconnect()
        } else if (connected == false) {
            isPiConnected = false
        }
        val busy = isCaptureOngoing || isPmfiRunning || (vm.isCalibrating.value == true)
        setUiBusy(busy && isPiConnected)
    }

    // ===== Socket handlers =====
    private fun hookSocketCore() {
        PiSocketManager.on("connect") {
            if (!isAdded) return@on
            requireActivity().runOnUiThread { updateConnUi(true) }
        }
        PiSocketManager.on("disconnect") { scheduleDebouncedDisconnect() }
        PiSocketManager.on("connect_error") { scheduleDebouncedDisconnect() }
        PiSocketManager.on("error") { scheduleDebouncedDisconnect() }
    }

    private fun hookCalibrationSocket() {
        PiSocketManager.on("cal_progress") { payload ->
            val j = payload as? JSONObject ?: return@on
            vm.updateCalibrationProgress(
                channelIndex = j.optInt("channel_index", 0),
                totalChannels = j.optInt("total_channels", 16),
                wavelengthNm = j.optInt("wavelength_nm", -1),
                averageIntensity = j.optDouble("average_intensity", -1.0),
                normPrev = j.optDouble("led_norm_prev", -1.0),
                normNew = j.optDouble("led_norm_new", -1.0),
            )
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                binding.calProgressBar.max = vm.calTotalChannels.value ?: 16
                binding.calProgressBar.progress = (vm.calChannelIndex.value ?: 0) + 1
                binding.calProgressBar.visibility = View.VISIBLE
                binding.calProgressText.visibility = View.VISIBLE
                val wl = vm.calWavelengthNm.value
                val ave = vm.calAverageIntensity.value
                val p = vm.calNormPrev.value
                val n = vm.calNormNew.value
                binding.calProgressText.text =
                    "Calibrating: ${binding.calProgressBar.progress}/${binding.calProgressBar.max} · ${wl ?: "-"}nm · avg=${ave?.let { String.format(Locale.US, "%.1f", it) } ?: "-"} · norm ${p?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}→${n?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}"
            }
        }
        PiSocketManager.on("cal_complete") { payload ->
            val j = payload as? JSONObject
            val norms = j?.optJSONArray("led_norms")
            if (norms != null && norms.length() == 16) {
                val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
                prefs.edit().putString("led_norms_json", norms.toString()).apply()
            }
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                toast("Calibration complete")
                vm.completeCalibration(emptyList())
                showCalUi(false)
                setUiBusy(false)
            }
        }
        PiSocketManager.on("cal_error") { payload ->
            val msg = (payload as? JSONObject)?.optString("message") ?: payload.toString()
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                toast("Calibration error: $msg")
                vm.failCalibration()
                showCalUi(false)
                setUiBusy(false)
            }
        }
    }

    private fun hookPmfiSocket() {
        // Planning info: sections + ETA
        PiSocketManager.on("pmfi.plan") { payload ->
            val j = payload as? JSONObject ?: return@on
            val total = j.optInt("total_frames", -1).coerceAtLeast(0)
            val eta   = j.optInt("eta_seconds", -1)

            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                isPmfiRunning = true
                setUiBusy(true)

                // Update status text
                val etaTxt = if (eta >= 0) " · ETA ~${eta}s" else ""
                binding.pmfiStatusText.text = "PMFI plan: $total frames$etaTxt"

                // Configure and show progress bar
                binding.pmfiProgressBar.max = if (total > 0) total else 100
                binding.pmfiProgressBar.progress = 0
                binding.pmfiProgressBar.visibility = View.VISIBLE

                // Mirror to VM (optional)
                vm.pmfiTotalFrames.value = total
                vm.pmfiDoneFrames.value = 0
            }
        }

        // Stage transitions: config/capturing/packing/uploading/done
        PiSocketManager.on("pmfi.stage") { payload ->
            val j = payload as? JSONObject ?: return@on
            val section = j.optString("section", "")
            val state   = j.optString("state", "")
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                isPmfiRunning = true
                setUiBusy(true)
                binding.pmfiStatusText.text = "PMFI: [$section] → $state"
                // Keep bar visible during section transitions
                if (binding.pmfiProgressBar.visibility != View.VISIBLE) {
                    binding.pmfiProgressBar.visibility = View.VISIBLE
                }
            }
        }

        // Fine-grained progress with FPS and ETA
        PiSocketManager.on("pmfi.progress") { payload ->
            val j = payload as? JSONObject ?: return@on
            val sect  = j.optString("section", "")
            val i     = j.optInt("frame_idx", 0)
            val n     = j.optInt("section_frames", 0)
            val done  = j.optInt("total_done", 0).coerceAtLeast(0)
            val total = j.optInt("total_frames", 0).coerceAtLeast(0)
            val fps   = j.optDouble("fps", Double.NaN)
            val eta   = j.optInt("eta_seconds", -1)

            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                isPmfiRunning = true
                setUiBusy(true)

                // Status line
                val fpsTxt = if (!fps.isNaN()) String.format(Locale.US, " · %.2f fps", fps) else ""
                val etaTxt = if (eta >= 0) " · ETA ${eta}s" else ""
                binding.pmfiStatusText.text =
                    "PMFI: [$sect] frame $i/$n · total $done/$total$fpsTxt$etaTxt"

                // Progress bar
                if (total > 0) binding.pmfiProgressBar.max = total
                binding.pmfiProgressBar.progress = done.coerceAtMost(binding.pmfiProgressBar.max)
                if (binding.pmfiProgressBar.visibility != View.VISIBLE) {
                    binding.pmfiProgressBar.visibility = View.VISIBLE
                }

                // Mirror to VM (optional)
                vm.pmfiTotalFrames.value = if (total > 0) total else vm.pmfiTotalFrames.value
                vm.pmfiDoneFrames.value = done
            }
        }

        // Optional: log stream (shows last log line appended)
        PiSocketManager.on("pmfi.log") { payload ->
            val j = payload as? JSONObject ?: return@on
            val line = j.optString("line", "")
            if (!isAdded || line.isBlank()) return@on
            requireActivity().runOnUiThread {
                binding.pmfiStatusText.text = "${binding.pmfiStatusText.text}\n$line"
            }
        }

        // Section ZIP successfully uploaded to phone
        PiSocketManager.on("pmfi.sectionUploaded") { payload ->
            val j = payload as? JSONObject ?: return@on
            val section = j.optString("section", "")
            val bytes = j.optLong("bytes", -1)
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                val sizeTxt = if (bytes > 0) " (${bytes}B)" else ""
                binding.pmfiStatusText.text = "Uploaded section: $section$sizeTxt"
            }
        }

        // Completion
        PiSocketManager.on("pmfi.complete") { payload ->
            val j = payload as? JSONObject
            val ok = j?.optBoolean("ok", false) ?: false
            val reason = j?.optString("reason", "")?.takeIf { it.isNotBlank() }
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                binding.pmfiStatusText.text =
                    if (ok) "PMFI complete (sections will appear in Gallery)"
                    else "PMFI stopped${reason?.let { " ($it)" } ?: ""}"
                isPmfiRunning = false
                setUiBusy(false)
                binding.pmfiProgressBar.visibility = View.GONE

                // Reset VM counters (optional)
                vm.pmfiTotalFrames.value = 0
                vm.pmfiDoneFrames.value = 0
            }
        }
    }


    private fun onPreviewImage(data: JSONObject, bmp: Bitmap) {
        cancelPendingDisconnect()
        if (!isAdded) return
        requireActivity().runOnUiThread {
            when (mode) {
                PreviewMode.LIVE_FEED -> {
                    liveImage?.setImageBitmap(bmp)
                }
                PreviewMode.IMAGE_CAPTURE -> {
                    var idx = data.optInt("index", -1)
                    if (idx !in 0..15) idx = data.optInt("idx", -1)
                    if (idx !in 0..15) idx = data.optInt("i", -1)
                    if (idx !in 0..15) idx = data.optInt("channel", -1)
                    if (idx !in 0..15) idx = data.optInt("led", -1)

                    if (idx !in 0..15) {
                        val current = vm.capturedBitmaps.value ?: List(16) { null }
                        idx = current.indexOfFirst { it == null }.takeIf { it >= 0 } ?: -1
                    }
                    if (idx in 0..15) vm.addBitmap(idx, bmp)
                }
                else -> Unit
            }
        }
    }


    private fun onStateUpdate(data: JSONObject) {
        cancelPendingDisconnect()
        val sw4 = data.optBoolean("sw4", false)
        val busy = data.optBoolean("busy", false)
        val calibrating = data.optBoolean("calibrating", false)
        val pmfi = data.optBoolean("pmfi_running", false)
        val lastBtn = data.optString("last_button", "")

        if (!isAdded) return
        requireActivity().runOnUiThread {
            binding.switchCameraPreview.setOnCheckedChangeListener(null)
            binding.switchCameraPreview.isChecked = sw4
            binding.switchCameraPreview.setOnCheckedChangeListener { _, checked ->
                val blocked = isCaptureOngoing || data.optBoolean("busy", false)
                        || data.optBoolean("calibrating", false)
                        || data.optBoolean("pmfi_running", false)
                if (blocked) {
                    binding.switchCameraPreview.isChecked = false
                    return@setOnCheckedChangeListener
                }
                triggerButton("SW4")
                if (checked) startLivePreview() else clearPreview()
            }

            isPmfiRunning = isPmfiRunning || pmfi
            val disableUi = isCaptureOngoing || busy || calibrating || isPmfiRunning
            setUiBusy(disableUi)

            if (lastBtn == "SW2" && !isCaptureOngoing && !isPmfiRunning) {
                startImageGrid()
                startCaptureUi()
                isCaptureOngoing = true
                vm.capturedBitmaps.value = MutableList(16) { null }
                vm.imageCount.value = 0
                vm.isCapturing.value = true
            }

            if (sw4 && mode != PreviewMode.LIVE_FEED) startLivePreview()
            if (!sw4 && mode == PreviewMode.LIVE_FEED) clearPreview()
            if (lastBtn.isNotBlank()) binding.lastPiButtonText.text = "MSI Button Pressed: $lastBtn"
        }
    }

    // ===== Actions =====
    private fun triggerButton(buttonId: String) {
        if (currentIp.isEmpty()) { toast("Set IP address first"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = PiApi.api.triggerButton(buttonId)
                if (!resp.isSuccessful) toast("Trigger $buttonId failed: ${resp.code()}")
            } catch (e: Exception) {
                toast("Network error: ${e.localizedMessage}")
            }
        }
    }


    private fun startPmfi(iniText: String) {
        val sessionId = UUID.randomUUID().toString()
        val b64 = Base64.encodeToString(iniText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = PiApi.api.pmfiStart(
                    PmfiStartBody(
                        ini_b64 = b64,
                        session_id = sessionId,
                        upload_mode = "zip"
                    )
                )
                if (resp.isSuccessful) {
                    isPmfiRunning = true
                    setUiBusy(true)
                    binding.pmfiStatusText.text = "PMFI started… (sessionId=$sessionId)"
                } else {
                    toast("PMFI start failed: ${resp.code()} ${resp.errorBody()?.string().orEmpty()}")
                }
            } catch (e: Exception) {
                toast("Network error: ${e.localizedMessage}")
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

    // ===== Preview UI helpers =====
    private fun startImageGrid() {
        clearPreview()
        mode = PreviewMode.IMAGE_CAPTURE
        previewScroll.visibility = View.VISIBLE
        val g = GridLayout(requireContext()).apply {
            rowCount = 4; columnCount = 4
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        gridImages.clear()
        repeat(16) {
            val iv = ImageView(requireContext()).apply {
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = 220
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
            }
            g.addView(iv); gridImages.add(iv)
        }
        grid = g
        previewContainer.removeAllViews()
        previewContainer.addView(g)
    }

    private fun updateGrid(bitmaps: List<Bitmap?>) {
        if (grid == null) return
        for (i in 0 until 16) {
            val iv = gridImages.getOrNull(i) ?: continue
            val bmp = bitmaps.getOrNull(i)
            if (bmp != null) iv.setImageBitmap(bmp)
            else iv.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun startCaptureUi() {
        binding.captureProgressBar.progress = 0
        binding.captureProgressText.text = "Receiving images: 0/16"
        binding.captureProgressBar.visibility = View.VISIBLE
        binding.captureProgressText.visibility = View.VISIBLE
    }

    private fun startLivePreview() {
        clearPreview()
        mode = PreviewMode.LIVE_FEED
        previewScroll.visibility = View.VISIBLE
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
        mode = PreviewMode.NONE
        previewScroll.visibility = View.GONE
        previewContainer.removeAllViews()
        liveImage = null
        grid = null
        gridImages.clear()
    }

    private fun showCalUi(show: Boolean) {
        if (show) {
            binding.calProgressBar.max = 16
            binding.calProgressBar.progress = 0
            binding.calProgressBar.visibility = View.VISIBLE
            binding.calProgressText.visibility = View.VISIBLE
            binding.calProgressText.text = "Calibrating: 0/16"
        } else {
            binding.calProgressBar.visibility = View.GONE
            binding.calProgressText.visibility = View.GONE
        }
    }

    private fun setUiBusy(busy: Boolean) {
        val connected = isPiConnected
        val calibrating = vm.isCalibrating.value == true
        val capturing = isCaptureOngoing
        val pmfi = isPmfiRunning

        val controlsEnabled = connected && !busy && !calibrating && !capturing && !pmfi
        binding.switchCameraPreview.isEnabled = controlsEnabled
        binding.buttonStartAmsi.isEnabled = controlsEnabled
        binding.buttonCalibrate.isEnabled = controlsEnabled
        binding.pmfiStartBtn.isEnabled = connected && !capturing && !calibrating && !pmfi
        binding.buttonShutdown.isEnabled = connected && !pmfi

        // IP controls when disconnected/idle
        val connIdle = !capturing && !calibrating && !pmfi
        binding.setIpButton.isEnabled = connIdle && !connected && !isConnecting
        binding.ipAddressInput.isEnabled = connIdle && !connected && !isConnecting
        binding.buttonDisconnect.isEnabled = connIdle && connected
    }

    private fun saveIp(ip: String) {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
        prefs.edit().putString("server_ip", ip).apply()
    }

    private fun restoreSavedIp() {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
        val saved = prefs.getString("server_ip", "") ?: ""
        if (saved.isNotEmpty()) {
            binding.ipAddressInput.setText(saved)
            setBaseUrls(saved)
        }
    }
}
