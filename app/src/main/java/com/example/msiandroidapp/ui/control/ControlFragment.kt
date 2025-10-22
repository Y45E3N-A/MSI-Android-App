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
import com.example.msiandroidapp.network.PmfiStartBody
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

    // Track preview state mirrored from server
    private var previewActive = false

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
    // --- ENV telemetry (latest + freshness tracking) ---
    private var latestTempC: Double? = null
    private var latestHumidity: Double? = null
    private var latestEnvIso: String? = null
    private var lastEnvEventAt: Long = 0L

    // Poll fallback every 20s if we haven't seen a socket event recently
    private val envPollMs = 20_000L

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


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ----- Cache a few views for preview area -----
        previewContainer = view.findViewById(R.id.preview_container)
        previewScroll    = view.findViewById(R.id.preview_scrollview)

        // ----- Initial UI + handlers -----
        setupButtons()
        observeUploadProgress()
        hookSocketCore()
        hookCalibrationSocket()
        hookEnvSocket()
        renderEnv(null, null, null)
        // Start from a safe idle visual state
        updateConnUi(null)
        resetPmfiUi(hide = true)
        setUiBusy(false)

        // ----- Restore saved IP and connect if present -----
        restoreSavedIp()
        if (currentIp.isNotEmpty()) {
            PiSocketManager.setBaseUrl(currentIp)
            PiSocketManager.connect(::onPreviewImage, ::onStateUpdate)
            PiSocketManager.emit("get_state", JSONObject())
        }

        // ----- Reset to start page + clear UI on connection loss -----
        PiSocketManager.setConnectionStateListener { connected ->
            if (!isAdded) return@setConnectionStateListener
            requireActivity().runOnUiThread {
                if (connected) {
                    updateConnUi(true)
                } else {
                    // Model → idle, UI → fresh, navigate to Control tab
                    vm.resetToIdle()
                    resetUiToFreshState()
                    (requireActivity() as? MainActivity)?.goToStartPage()

                }
            }
        }

        // ----- Restore any in-flight AMSI capture UI -----
        val restoredCount = vm.imageCount.value ?: 0
        val restoredImgs  = vm.capturedBitmaps.value ?: List(16) { null }
        if (restoredCount in 1..16 || restoredImgs.any { it != null }) {
            startImageGrid()
            updateGrid(restoredImgs)
            binding.captureProgressBar.max = 16
            binding.captureProgressBar.progress = restoredCount
            binding.captureProgressText.text = "Receiving images: $restoredCount/16"
            binding.captureProgressBar.visibility = View.VISIBLE
            binding.captureProgressText.visibility = View.VISIBLE
        }

        // ----- LiveData: AMSI image grid + count -----
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
                c == 16 -> binding.captureProgressText.text = "All images received!"
                else -> {
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                }
            }
        }

        // ================================
        // PMFI SOCKET EVENT BINDINGS
        // ================================
        // Plan (now includes section_count)
        // Plan (now includes section_count)
        PiSocketManager.on("pmfi.plan") { payload ->
            val j = payload as org.json.JSONObject
            val totalFrames  = j.optInt("total_frames", 0)
            val sectionCount = j.optInt("section_count", 0)

            // Model
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

            // Reset cumulative/rollover guards for a brand-new run
            pmfiCumOffset = 0
            pmfiLastTot   = 0
            lastSectionIndexForTot = -1
            lastSecDoneForTot = 0

            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                // Hide any AMSI UI remnants
                binding.captureProgressBar.progress = 0
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.text = ""
                binding.captureProgressText.visibility = View.GONE
                isCaptureOngoing = false
                vm.isCapturing.value = false
                vm.imageCount.value = 0

                resetPmfiUi(hide = false)
                isPmfiRunning = true
                setUiBusy(true)
                setPmfiButtonBusy(true)
                binding.pmfiStartBtn.text = "PMFI running…"
                showPmfiUi()
            }
        }


        // Stage (capturing/packing/uploaded…)
        PiSocketManager.on("pmfi.stage") { payload ->
            val j = payload as org.json.JSONObject
            vm.pmfiCurrentSection.postValue(j.optString("section", null))
            vm.pmfiSectionState.postValue(j.optString("state", ""))
        }

        // Progress (overall + per-section)
        // Progress (overall + per-section, with safe cumulative logic)
        PiSocketManager.on("pmfi.progress") { payload ->
            val j = payload as org.json.JSONObject

            val secIdx0  = j.optInt("section_index", 0)
            val secDone  = j.optInt("section_done", j.optInt("frame_idx", 0))
            val secTotal = j.optInt("section_frames", 0)
            val rawTot   = j.optInt("total_done", 0)
            val totAll   = j.optInt("total_frames", 0)

            // Determine a real boundary: new section OR a clean zip boundary (secDone==0 and previous zip finished)
            val hasValidSecTotal = secTotal > 0
            val finishedPrevZip  = hasValidSecTotal && lastSecDoneForTot >= (secTotal - 1)
            val startedNewZipOrSection =
                (secIdx0 > lastSectionIndexForTot) || (secDone == 0 && finishedPrevZip)

            // Only compensate rollover if we genuinely crossed a boundary
            if (rawTot < pmfiLastTot && startedNewZipOrSection) {
                pmfiCumOffset += pmfiLastTot
            }
            pmfiLastTot = rawTot
            lastSectionIndexForTot = secIdx0
            lastSecDoneForTot = secDone

            val safeTotAll = totAll.coerceAtLeast(0)
            val safeRawTot = rawTot.coerceAtLeast(0)
            val totDoneCumulative = (safeRawTot + pmfiCumOffset).coerceAtMost(safeTotAll)

            val secPct = if (secTotal > 0) ((secDone.toDouble() / secTotal) * 100).toInt() else 0
            val totPct = if (safeTotAll > 0) ((totDoneCumulative.toDouble() / safeTotAll) * 100).toInt() else 0

            vm.pmfiCurrentSection.postValue(j.optString("section", null))
            vm.pmfiSectionIndex.postValue(secIdx0)
            vm.pmfiSectionDone.postValue(secDone)
            vm.pmfiSectionTotal.postValue(secTotal)
            vm.pmfiSectionPercent.postValue(secPct)
            vm.pmfiSectionInfo.postValue(j.optString("section_info", null))

            // Post the cumulative values, not the raw
            vm.pmfiDoneFrames.postValue(totDoneCumulative)
            vm.pmfiTotalFrames.postValue(safeTotAll)
            vm.pmfiPercent.postValue(totPct)
        }



        // Section uploaded (toast)
        PiSocketManager.on("pmfi.sectionUploaded") { payload ->
            val j = payload as org.json.JSONObject
            val section = j.optString("section", "")
            val bytes   = j.optLong("bytes", -1L)
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                val human = if (bytes > 0) String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f)) else "uploaded"
                Toast.makeText(requireContext(), "$section $human", Toast.LENGTH_SHORT).show()
            }
        }

        // Optional log
        PiSocketManager.on("pmfi.log") { payload ->
            vm.pmfiLogLine.postValue((payload as org.json.JSONObject).optString("line"))
        }

        // Complete
        PiSocketManager.on("pmfi.complete") { payload ->
            val ok = (payload as org.json.JSONObject).optBoolean("ok", true)
            vm.pmfiComplete.postValue(ok)
            vm.pmfiPercent.postValue(100)
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                pmfiCumOffset = 0
                pmfiLastTot   = 0
                isPmfiRunning = false
                setUiBusy(false)
                setPmfiButtonBusy(false)
                binding.pmfiStartBtn.text = "Start PMFI"
            }
        }

        // ================================
        // PMFI LiveData → UI
        // ================================
        // Current section label
        vm.pmfiCurrentSection.observe(viewLifecycleOwner) { s ->
            binding.pmfiSectionLabel.text = s?.let { "Current section: $it" } ?: "PMFI idle"
        }

// Overall percent (non-animated)
        vm.pmfiPercent.observe(viewLifecycleOwner) { p ->
            val v = p ?: 0
            binding.pmfiProgressBar.setProgressFast(v)
            if (binding.pmfiProgressBar.visibility != View.VISIBLE && v > 0) {
                binding.pmfiProgressBar.visibility = View.VISIBLE
            }
        }

// Overall counter text
        vm.pmfiDoneFrames.observe(viewLifecycleOwner) { done ->
            val total = vm.pmfiTotalFrames.value ?: 0
            val d = done ?: 0
            val pct = if (total > 0) (d * 100 / total) else 0
            binding.pmfiCounter.text = "$d / $total (${pct}%)"
        }

// Stage
        vm.pmfiSectionState.observe(viewLifecycleOwner) { st ->
            binding.pmfiStageLabel.text = st ?: ""
        }

// Per-section info + progress (non-animated)
        vm.pmfiSectionInfo.observe(viewLifecycleOwner) { info ->
            binding.tvSectionInfo?.text = info ?: "—"
        }

// Instant reset when section index increases (eliminates "carry-over" lag)
        var lastSectionIndex = -1
        vm.pmfiSectionIndex.observe(viewLifecycleOwner) { idx0 ->
            val idx = idx0 ?: 0
            val count = vm.pmfiSectionCount.value ?: 0
            binding.tvSectionsCount?.text = "Sections: ${idx + 1}/$count"

            if (idx != lastSectionIndex) {
                // New section → zero the bar & text immediately
                binding.pbSection?.setProgressFast(0)
                binding.tvSectionFrames?.text = "0/0 (0%) in this zip"
                lastSectionIndex = idx
            }
        }

        vm.pmfiSectionDone.observe(viewLifecycleOwner) { done ->
            val total = vm.pmfiSectionTotal.value ?: 0
            val d = done ?: 0
            val pct = if (total > 0) (d * 100 / total) else 0
            binding.pbSection?.max = 100
            binding.pbSection?.setProgressFast(pct)
            binding.tvSectionFrames?.text = "$d/$total (${pct}%) in this zip"
        }

// Completion → immediate reset to idle visuals
        vm.pmfiComplete.observe(viewLifecycleOwner) { ok ->
            if (ok == true) {
                binding.pmfiStageLabel.text = "Complete"
                // Snap to 100, then fast reset a moment later
                binding.pmfiProgressBar.setProgressFast(100)
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(300) // tiny grace so user sees 100%
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

    private fun resetUiToFreshState() {
        // Connection strip
        binding.piConnectionStatus.text = "Status: Unknown"
        binding.piConnectionDot.setBackgroundResource(R.drawable.circle_grey)

        // Last button & switches
        binding.lastPiButtonText.text = "MFI Button Pressed: --"
        binding.switchCameraPreview.isChecked = false
        binding.switchCameraPreview.isEnabled = false

        // AMSI progress
        binding.captureProgressBar.progress = 0
        binding.captureProgressBar.visibility = View.GONE
        binding.captureProgressText.text = ""
        binding.captureProgressText.visibility = View.GONE

        // Calibration progress
        binding.calProgressBar.progress = 0
        binding.calProgressBar.visibility = View.GONE
        binding.calProgressText.text = ""
        binding.calProgressText.visibility = View.GONE

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
        previewScroll.scrollTo(0, 0)
        view?.findViewById<ScrollView>(R.id.controls_scrollview)?.scrollTo(0, 0)

        // Inputs / buttons
        binding.setIpButton.isEnabled = true
        binding.ipAddressInput.isEnabled = true
        binding.buttonDisconnect.isEnabled = false
        binding.buttonStartAmsi.isEnabled = false
        binding.buttonCalibrate.isEnabled = false
        binding.buttonShutdown.isEnabled = false
    }
    private val pmfiEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val txt = res.data?.getStringExtra(PmfiEditorActivity.EXTRA_RESULT_TEXT) ?: return@registerForActivityResult
            binding.pmfiIniEdit.setText(txt)
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
    private suspend fun stopPreviewAndAwaitAck(timeoutMs: Long = 2500L) {
        // If we already know preview is off, just return.
        if (!previewActive) return

        // Ask for latest state first (in case UI is stale)
        PiSocketManager.emit("get_state", JSONObject())

        // If still on, toggle SW4 OFF explicitly
        if (previewActive) {
            triggerButton("SW4")
        }

        // Wait for state_update to confirm preview stopped
        val start = System.currentTimeMillis()
        while (previewActive && (System.currentTimeMillis() - start) < timeoutMs) {
            delay(40)
        }
    }

    // ===== small helpers for PMFI UI text =====
    private fun fmtPair(a: Int?, b: Int?): String =
        if (a != null && b != null) "$a/$b" else "0/0"

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
        binding.pmfiExpandBtn.setOnClickListener {
            val ctx = requireContext()
            val intent = Intent(ctx, PmfiEditorActivity::class.java).apply {
                putExtra(PmfiEditorActivity.EXTRA_TEXT, binding.pmfiIniEdit.text?.toString().orEmpty())
            }
            pmfiEditorLauncher.launch(intent)
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
                        .setMessage("Disconnecting will abort the 16-image capture. Disconnect anyway?")
                        .setPositiveButton("Disconnect") { _, _ -> forceDisconnect() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                else -> forceDisconnect()
            }
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

        // AMSI (SW2) — with preview-off await
        binding.buttonStartAmsi.setOnClickListener {
            if (!isPiConnected || isCaptureOngoing || isPmfiRunning || vm.isCalibrating.value == true) {
                toast("Pi not connected or busy"); return@setOnClickListener
            }

            // 1) Prepare UI immediately so the grid is visible even while we wait
            startImageGrid()
            startCaptureUi()
            isCaptureOngoing = true
            setUiBusy(true)
            vm.capturedBitmaps.value = MutableList(16) { null }
            vm.imageCount.value = 0
            vm.isCapturing.value = true
            // Kill any lingering live preview image in the container
            clearLiveOnly()

            // 2) Ensure the preview is actually OFF on the Pi before we start AMSI
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Ask Pi for a fresh state, then stop preview and await ack
                    PiSocketManager.emit("get_state", JSONObject())
                    stopPreviewAndAwaitAck(timeoutMs = 2500)

                    // 3) Now it's safe to start AMSI (camera_lock will be free on the Pi)
                    triggerButton("SW2")
                } catch (e: Exception) {
                    toast("Failed to start capture: ${e.localizedMessage}")
                    // Roll back the UI if something went wrong
                    isCaptureOngoing = false
                    setUiBusy(false)
                }
            }
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

        // PMFI start (INI text → base64 → Pi)
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
            // Ignore upload progress while PMFI or calibration is active,
            // or if we're not in an AMSI capture UI state.
            if (isPmfiRunning || (vm.isCalibrating.value == true) || !isCaptureOngoing) return@observe

            Log.d(TAG, "Upload progress $sessionId : $count")
            vm.imageCount.value = count
            binding.captureProgressBar.progress = count
            binding.captureProgressText.text = "Receiving images: $count/16"

            if (count == 16) {
                vm.isCapturing.value = false
                binding.captureProgressText.text = "All images received!"
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


    // ===== Await helper: wait for preview to drop =====
    private suspend fun waitForPreviewOff(timeoutMs: Long = 1500L) {
        val start = System.currentTimeMillis()

        // Fast local UX and server hint:
        if (previewActive && binding.switchCameraPreview.isChecked) {
            binding.switchCameraPreview.isChecked = false
            clearPreview()
            triggerButton("SW4")
        }

        // Small grace so the Pi can stop & join its preview thread
        while (previewActive && (System.currentTimeMillis() - start) < timeoutMs) {
            delay(50)
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


                        val now = System.currentTimeMillis()
                        if (now - lastEnvEventAt > envPollMs) {
                            pollEnvOnce()
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
                requireActivity().runOnUiThread { updateConnUi(true) }
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
                        requireActivity().runOnUiThread {
                            renderEnv(latestTempC, latestHumidity, latestEnvIso)
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
        stopPolling()
        cancelPendingDisconnect()
        try { PiSocketManager.disconnect() } catch (_: Exception) {}

        // Local flags
        isPiConnected = false
        isConnecting = false
        isCaptureOngoing = false
        isPmfiRunning = false
        previewActive = false

        // Model → idle
        vm.resetToIdle()

        // UI → fresh start
        resetUiToFreshState()
        resetPmfiUi(hide = true)
        // Navigate to the start page/tab
        (activity as? com.example.msiandroidapp.MainActivity)?.goToStartPage()

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

    // ===== Socket handlers (core + calibration) =====
    private fun hookSocketCore() {
        PiSocketManager.on("connect") {
            if (!isAdded) return@on
            requireActivity().runOnUiThread { updateConnUi(true) }
        }
        PiSocketManager.on("disconnect") { scheduleDebouncedDisconnect() }
        PiSocketManager.on("connect_error") { scheduleDebouncedDisconnect() }
        PiSocketManager.on("error") { scheduleDebouncedDisconnect() }
    }
    // ===== Environment (Temp / Humidity) socket + render =====
    private fun hookEnvSocket() {
        // Socket event from Pi: "env.update" with { temp_c, humidity, ts_utc }
        PiSocketManager.on("env.update") { payload ->
            val j = payload as org.json.JSONObject
            val t = j.optDouble("temp_c", Double.NaN)
            val h = j.optDouble("humidity", Double.NaN)
            val ts = j.optString("ts_utc", null)

            latestTempC = if (t.isNaN()) null else t
            latestHumidity = if (h.isNaN()) null else h
            latestEnvIso = ts
            lastEnvEventAt = System.currentTimeMillis()

            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                renderEnv(latestTempC, latestHumidity, latestEnvIso)
            }
        }
    }

    // Pretty-print on the Control tab
    private fun renderEnv(tempC: Double?, rh: Double?, iso: String?) {
        val tLbl = if (tempC == null) "Temp: —" else String.format(Locale.UK, "Temp: %.1f °C", tempC)
        val hLbl = if (rh == null)    "RH: —"   else String.format(Locale.UK, "RH: %.0f %%", rh)

        // New visible chips
        binding.topTempChip.text = tLbl
        binding.topHumChip.text  = hLbl

        // Legacy labels (hidden, but harmless to keep in sync)
        binding.envTempText.text     = tLbl
        binding.envHumidityText.text = hLbl
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

    // ===== Preview / state callbacks =====
    private fun onPreviewImage(data: JSONObject, bmp: Bitmap) {
        cancelPendingDisconnect()
        if (!isAdded) return
        requireActivity().runOnUiThread {
            when (mode) {
                PreviewMode.LIVE_FEED -> liveImage?.setImageBitmap(bmp)
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
        // Parse server state
        val sw4          = data.optBoolean("sw4", false)               // server-side preview flag
        val busy         = data.optBoolean("busy", false)
        val calibrating  = data.optBoolean("calibrating", false)
        val pmfi         = data.optBoolean("pmfi_running", false)
        val lastBtn      = data.optString("last_button", "")
        // Optional extras (won't crash if absent)
        val pmfiStage    = data.optString("pmfi_stage", "")
        val pmfiSection  = data.optString("pmfi_section", "")

        // Mirror preview state bit for wait helpers
        previewActive = sw4

        if (!isAdded) return
        requireActivity().runOnUiThread {
            // We're receiving a socket event -> mark as connected
            updateConnUi(true)

            // ---- Update lightweight labels ----
            if (lastBtn.isNotBlank()) {
                binding.lastPiButtonText.text = "MFI Button Pressed: $lastBtn"
            }
            if (pmfiStage.isNotBlank()) binding.pmfiStageLabel.text = pmfiStage
            if (pmfiSection.isNotBlank()) binding.pmfiSectionLabel.text = "Current section: $pmfiSection"

            // ---- Recompute local busy flags (don't override capture/pmfi already set by UI flows) ----
            isPmfiRunning = isPmfiRunning || pmfi
            val uiBusy = isCaptureOngoing || calibrating || isPmfiRunning || busy
            setUiBusy(uiBusy)

            // ---- Keep the preview switch in sync BUT never let it fight capture/pmfi/cal ----
            binding.switchCameraPreview.setOnCheckedChangeListener(null)
            binding.switchCameraPreview.isChecked = sw4
            binding.switchCameraPreview.setOnCheckedChangeListener { _, checked ->
                val blocked = isCaptureOngoing || (vm.isCalibrating.value == true) || isPmfiRunning
                if (blocked) {
                    // Snap back; we don’t allow live preview changes while busy
                    binding.switchCameraPreview.isChecked = previewActive
                    return@setOnCheckedChangeListener
                }
                triggerButton("SW4")
                if (checked) startLivePreview() else clearPreview()
            }

            // ---- Guard: do NOT touch preview UI while capturing/PMFI/calibrating ----
            val allowPreviewUiChanges = !isCaptureOngoing && !isPmfiRunning && (vm.isCalibrating.value != true)
            if (allowPreviewUiChanges) {
                // Mirror server preview state into UI only when idle
                when {
                    sw4 && mode != PreviewMode.LIVE_FEED -> startLivePreview()
                    !sw4 && mode == PreviewMode.LIVE_FEED -> clearPreview()
                }
            }

            // ---- If Pi initiated AMSI (SW2), bootstrap capture UI once ----
            if (lastBtn == "SW2" && !isCaptureOngoing && !isPmfiRunning && (vm.isCalibrating.value != true)) {
                startImageGrid()
                startCaptureUi()
                isCaptureOngoing = true
                vm.capturedBitmaps.value = MutableList(16) { null }
                vm.imageCount.value = 0
                vm.isCapturing.value = true

                // Ensure preview visuals are cleared so the grid stays visible
                clearLiveOnly()
            }
        }
    }

    private fun clearLiveOnly() {
        if (mode == PreviewMode.LIVE_FEED) {
            previewContainer.removeAllViews()
            liveImage = null
            mode = PreviewMode.NONE
            // keep previewScroll VISIBLE; don't touch grid vars
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
                val resp = PiApi.api.pmfiStart(PmfiStartBody(ini_b64 = b64, session_id = sessionId, upload_mode = "zip"))
                if (resp.isSuccessful) {
                    isPmfiRunning = true
                    setUiBusy(true)
                    binding.pmfiStartBtn.text = "PMFI running…"
                    binding.pmfiStageLabel.text = "PMFI started… (sessionId=$sessionId)"
                    resetPmfiUi(hide = false)   // show clean bars immediately
                    showPmfiUi()

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

    // Keep preview switch disabled while busy; only enable when truly idle
    // In setUiBusy(busy: Boolean)
    private fun setUiBusy(busy: Boolean) {
        val connected   = isPiConnected
        val connecting  = isConnecting
        val calibrating = vm.isCalibrating.value == true
        val capturing   = isCaptureOngoing
        val pmfi        = isPmfiRunning

        // --- Camera preview switch ---
        val switchEnabled = connected && !capturing && !pmfi && !calibrating && !busy
        binding.switchCameraPreview.isEnabled = switchEnabled

        // --- AMSI / Calibration ---
        val normalEnabled = connected && !capturing && !pmfi && !calibrating && !busy
        binding.buttonStartAmsi.isEnabled = normalEnabled
        binding.buttonCalibrate.isEnabled = normalEnabled

        // --- PMFI upload (Upload INI) ---
        val canStartPmfi = connected && !capturing && !calibrating && !pmfi && !busy
        binding.pmfiStartBtn.isEnabled = canStartPmfi
        binding.pmfiStartBtn.isClickable = canStartPmfi
        binding.pmfiStartBtn.alpha = if (canStartPmfi) 1f else 0.5f
        binding.pmfiStartBtn.text = if (pmfi) "PMFI running…" else "Start PMFI"

        // Keep INI text editable even while PMFI is running
        binding.pmfiIniEdit.isEnabled = true

        // --- Shutdown button ---
        binding.buttonShutdown.isEnabled = connected && !pmfi

        // --- Disconnect button ---
        // Always enabled when connected or connecting (never greyed out during busy states)
        binding.buttonDisconnect.isEnabled = connected || connecting
        binding.buttonDisconnect.alpha = if (binding.buttonDisconnect.isEnabled) 1f else 0.5f

        // --- IP input and connect button ---
        val connIdle = !capturing && !calibrating && !pmfi
        binding.setIpButton.isEnabled    = connIdle && !connected && !connecting
        binding.ipAddressInput.isEnabled = connIdle && !connected && !connecting
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
