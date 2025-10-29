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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private var isCalibratingOngoing = false

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
        super.onViewCreated(view, savedInstanceState)

        // ----- Cache a few views for preview area -----
        previewContainer = view.findViewById(R.id.preview_container)
        previewScroll    = view.findViewById(R.id.preview_scrollview)

        // ===== Initial UI wiring / click handlers =====
        setupButtons()
        observeUploadProgress()

        // ===== Socket listeners (core connection + env + battery + cal + amsi + pmfi) =====
        hookSocketCore()      // "connect", "disconnect", "error" → updateConnUi(...)
        hookEnvSocket()       // "env.update" → renderEnv(...)
        hookBatterySocket()   // "battery.update" → renderBatteryFromJson(...)

        // --- AMSI abort/error from Pi ---
        PiSocketManager.on("amsi_error") { _payload ->
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                // kill AMSI UI and release busy lock
                isCaptureOngoing = false
                vm.isCapturing.value = false

                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE

                clearPreview() // dump any partial preview/grid
                setUiBusy(false)
                toast("Capture aborted")
            }
        }

        // --- Calibration progress / complete / error from Pi ---
        // We'll inline what used to be hookCalibrationSocket(), but with the fixed cleanup-on-error
        PiSocketManager.on("cal_progress") { payload ->
            val j = payload as? JSONObject ?: return@on
            vm.updateCalibrationProgress(
                channelIndex      = j.optInt("channel_index", 0),
                totalChannels     = j.optInt("total_channels", 16),
                wavelengthNm      = j.optInt("wavelength_nm", -1),
                averageIntensity  = j.optDouble("average_intensity", -1.0),
                normPrev          = j.optDouble("led_norm_prev", -1.0),
                normNew           = j.optDouble("led_norm_new", -1.0),
            )
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                binding.calProgressBar.max = vm.calTotalChannels.value ?: 16
                binding.calProgressBar.progress = (vm.calChannelIndex.value ?: 0) + 1
                binding.calProgressBar.visibility = View.VISIBLE
                binding.calProgressText.visibility = View.VISIBLE

                val wl  = vm.calWavelengthNm.value
                val ave = vm.calAverageIntensity.value
                val p   = vm.calNormPrev.value
                val n   = vm.calNormNew.value

                binding.calProgressText.text =
                    "Calibrating: ${binding.calProgressBar.progress}/${binding.calProgressBar.max} · " +
                            "${wl ?: "-"}nm · avg=${ave?.let { String.format(Locale.US, "%.1f", it) } ?: "-"} · " +
                            "norm ${p?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}→" +
                            "${n?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}"
            }
        }

        PiSocketManager.on("cal_complete") { payload ->
            val j = payload as? JSONObject
            val norms = j?.optJSONArray("led_norms")
            if (norms != null && norms.length() == 16) {
                val prefs = requireActivity()
                    .getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("led_norms_json", norms.toString())
                    .apply()
            }
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                toast("Calibration complete")
                vm.completeCalibration(emptyList())
                showCalUi(false)

                // fully idle now
                isCaptureOngoing = false
                isCalibratingOngoing = false
                isPmfiRunning = false
                vm.isCapturing.value = false
                setUiBusy(false)
            }
        }

        PiSocketManager.on("cal_error") { _payload ->
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                // mark calibration as failed and free UI
                vm.failCalibration()
                showCalUi(false)

                isCaptureOngoing = false
                isPmfiRunning = false
                vm.isCapturing.value = false
                isCalibratingOngoing = false

                setUiBusy(false)
                toast("Calibration aborted")
            }
        }

        // --- PMFI SOCKET EVENT BINDINGS ---
        PiSocketManager.on("pmfi.plan") { payload ->
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

            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                // hide any AMSI remnants
                binding.captureProgressBar.progress = 0
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.text = ""
                binding.captureProgressText.visibility = View.GONE
                isCaptureOngoing = false
                vm.isCapturing.value = false
                vm.imageCount.value = 0

                // show PMFI UI
                resetPmfiUi(hide = false)
                isPmfiRunning = true
                setUiBusy(true)
                setPmfiButtonBusy(true)
                binding.pmfiStartBtn.text = "PMFI running…"
                showPmfiUi()
            }
        }

        PiSocketManager.on("pmfi.stage") { payload ->
            val j = payload as JSONObject
            vm.pmfiCurrentSection.postValue(j.optString("section", null))
            vm.pmfiSectionState.postValue(j.optString("state", ""))
        }

        PiSocketManager.on("pmfi.progress") { payload ->
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

        PiSocketManager.on("pmfi.sectionUploaded") { payload ->
            val j = payload as JSONObject
            val section = j.optString("section", "")
            val bytes   = j.optLong("bytes", -1L)
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
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

        PiSocketManager.on("pmfi.log") { payload ->
            vm.pmfiLogLine.postValue((payload as JSONObject).optString("line"))
        }

        PiSocketManager.on("pmfi.complete") { payload ->
            val ok = (payload as JSONObject).optBoolean("ok", true)
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
            requireActivity().runOnUiThread {
                if (connected) {
                    updateConnUi(true)
                } else {
                    // model → idle
                    vm.resetToIdle()

                    // UI → fresh, navigate to start tab
                    resetUiToFreshState()
                    (requireActivity() as? MainActivity)?.goToStartPage()
                }
            }
        }

        // ===== Restore any in-flight AMSI capture UI from ViewModel (rotation, etc.) =====
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

        // ===== LiveData observers → keep UI reactive =====
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
        return isCaptureOngoing ||
                isPmfiRunning   ||
                isCalibratingOngoing ||
                (vm.isCalibrating.value == true)
    }


    private fun resetUiToFreshState() {
        // Connection strip
        binding.piConnectionStatus.text = "Status: Unknown"
        binding.piConnectionDot.setBackgroundResource(R.drawable.circle_grey)

        // ---- Clear battery chip ----
        binding.chipBattery.text = "— %"
        binding.chipBattery.setChipIconResource(R.drawable.ic_battery_unknown_24)

        // ---- Clear env chips ----
        binding.topTempChip.text = "Temp: —"
        binding.topHumChip.text  = "RH: —"

        // Keep legacy hidden labels in sync too (just so nothing downstream explodes)
        binding.envTempText.text     = "Temp: —"
        binding.envHumidityText.text = "RH: —"

        // Last button & switches
        binding.lastPiButtonText.text = "MFi Button Pressed: --"
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
        // Preview toggle (SW4)
        attachPreviewToggleListener()


        // AMSI (SW2) — with preview-off await
        binding.buttonStartAmsi.setOnClickListener {
            // Step 0: if Pi not connected, just toast and leave everything as-is
            if (!isPiConnected) {
                toast("Pi not connected")
                return@setOnClickListener
            }

            // Step 1: try to grab the global busy lock *atomically*
            val gotLock = tryBeginBusy("amsi")
            if (!gotLock) {
                // someone else is already running, so just reflect that in UI
                toast("Busy")
                return@setOnClickListener
            }

            // Step 2: we own the lock now. Immediately reflect capture state in ViewModel/UI.
            vm.isCapturing.value = true
            startImageGrid()
            startCaptureUi()
            vm.capturedBitmaps.value = MutableList(16) { null }
            vm.imageCount.value = 0
            clearLiveOnly()

            // Also hard-disable the AMSI button itself to nuke tap spam visuals.
            binding.buttonStartAmsi.isEnabled = false
            binding.buttonStartAmsi.alpha = 0.4f

            // Step 3: launch the actual start sequence async
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    PiSocketManager.emit("get_state", JSONObject())
                    stopPreviewAndAwaitAck(timeoutMs = 2500)
                    triggerButton("SW2")
                    // success path: stay busy. We do NOT undo the lock here.
                } catch (e: Exception) {
                    toast("Failed to start capture: ${e.localizedMessage}")

                    // rollback the lock because nothing actually started
                    isCaptureOngoing = false
                    vm.isCapturing.value = false
                    setUiBusy(false)

                    // give button back so user can retry
                    binding.buttonStartAmsi.isEnabled = true
                    binding.buttonStartAmsi.alpha = 1f
                }
            }
        }





        // Calibration (SW3)
        binding.buttonCalibrate.setOnClickListener {
            if (!isPiConnected) {
                toast("Not connected")
                return@setOnClickListener
            }

            val gotLock = tryBeginBusy("cal")
            if (!gotLock) {
                toast("Busy")
                return@setOnClickListener
            }

            // reflect calibration UI instantly
            vm.startCalibration(totalChannels = 16)
            showCalUi(true)

            // disable the Cal button to kill visual spam
            binding.buttonCalibrate.isEnabled = false
            binding.buttonCalibrate.alpha = 0.4f

            // tell the Pi
            triggerButton("SW3")
            // cleanup happens later in cal_complete / cal_error, where you already:
            //  - set isCalibratingOngoing=false
            //  - setUiBusy(false)
        }




        // PMFI start (INI text → base64 → Pi)
        binding.pmfiStartBtn.setOnClickListener {
            if (!isPiConnected) {
                toast("Not connected")
                return@setOnClickListener
            }

            val iniText = binding.pmfiIniEdit.text?.toString()?.trim().orEmpty()
            if (iniText.isBlank()) {
                toast("Paste an INI first")
                return@setOnClickListener
            }

            val gotLock = tryBeginBusy("pmfi")
            if (!gotLock) {
                toast("Busy")
                return@setOnClickListener
            }

            // disable button immediately to kill spam visuals
            binding.pmfiStartBtn.isEnabled = false
            binding.pmfiStartBtn.isClickable = false
            binding.pmfiStartBtn.alpha = 0.4f
            binding.pmfiStartBtn.text = "PMFI running…"

            // if preview was on, tell Pi to stop it
            if (binding.switchCameraPreview.isChecked) {
                triggerButton("SW4")
            }

            // Actually start PMFI
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
    private fun attachPreviewToggleListener() {
        binding.switchCameraPreview.setOnCheckedChangeListener { _, checked ->
            // Block if busy or disconnected
            if (isGlobalBusy() || !isPiConnected) {
                // snap UI back to real state without recursively calling ourselves forever
                binding.switchCameraPreview.setOnCheckedChangeListener(null)
                binding.switchCameraPreview.isChecked = previewActive
                attachPreviewToggleListener()
                return@setOnCheckedChangeListener
            }

            // Safe to toggle preview on the Pi
            triggerButton("SW4")
            if (checked) {
                startLivePreview()
            } else {
                clearPreview()
            }
        }
    }

    private fun observeUploadProgress() {
        UploadProgressBus.uploadProgress.observe(viewLifecycleOwner) { (sessionId, count) ->
            // Ignore AMSI UI updates if we're not actually in an AMSI capture state
            // (or if PMFI / calibration is taking over the UI).
            if (isPmfiRunning || (vm.isCalibrating.value == true) || !isCaptureOngoing) return@observe

            Log.d(TAG, "Upload progress $sessionId : $count")

            vm.imageCount.value = count
            binding.captureProgressBar.progress = count
            binding.captureProgressText.text = "Receiving images: $count/16"

            when {
                count in 1..15 -> {
                    // mid-transfer
                    vm.isCapturing.value = true
                    binding.captureProgressBar.visibility = View.VISIBLE
                    binding.captureProgressText.visibility = View.VISIBLE
                }

                count == 16 -> {
                    // finished AMSI capture (all 16 PNGs uploaded)
                    vm.isCapturing.value = false
                    binding.captureProgressText.text = "All images received!"

                    // --- NEW BIT: compute total MB of this run and toast it ---
                    viewLifecycleOwner.lifecycleScope.launch {
                        val humanSize: String? = withContext(Dispatchers.IO) {
                            try {
                                val db  = com.example.msiandroidapp.data.AppDatabase.getDatabase(requireContext())
                                val dao = db.sessionDao()

                                // This uses the new suspend fun getMostRecentSession()
                                val latest: com.example.msiandroidapp.data.Session? =
                                    dao.getMostRecentSession()

                                if (latest != null) {
                                    // Sum the actual files we know about in imagePaths
                                    val totalBytes = latest.imagePaths.sumOf { pathStr ->
                                        try {
                                            val f = java.io.File(pathStr)
                                            if (f.exists() && f.isFile) f.length() else 0L
                                        } catch (_: Exception) {
                                            0L
                                        }
                                    }

                                    val mb = totalBytes / (1024.0 * 1024.0)
                                    String.format(Locale.getDefault(), "%.1f MB", mb)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not calc AMSI size", e)
                                null
                            }
                        }

                        if (isAdded && humanSize != null) {
                            Toast.makeText(
                                requireContext(),
                                "AMSI saved $humanSize",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        // After a short delay, do the same cleanup you already had
                        delay(1500)
                        if (!isAdded) return@launch
                        binding.captureProgressBar.visibility = View.GONE
                        binding.captureProgressText.visibility = View.GONE
                        clearPreview()
                        isCaptureOngoing = false
                        setUiBusy(false)
                    }
                }

                else -> {
                    // 0 or weird (safety fallback)
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                }
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
    // --- Battery telemetry (freshness tracking) ---
    private var lastBatteryEventAt: Long = 0L
    private val batteryPollMs = 10_000L

    private fun hookBatterySocket() {
        // payload is the snapshot (server emits it flat)
        PiSocketManager.on("battery.update") { payload ->
            val root = payload as org.json.JSONObject
            lastBatteryEventAt = System.currentTimeMillis()
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                renderBatteryFromJson(root)
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
                        requireActivity().runOnUiThread {
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
        val present = if (snap.has("present")) snap.optBoolean("present") else null
        val soc     = snap.optIntOrNull("soc_pct")
        val volt    = snap.optDoubleOrNull("voltage_v")

        // Choose icon
        val iconRes = when (state) {
            "CHARGING"     -> R.drawable.ic_battery_charging_24
            "FAULT"        -> R.drawable.ic_battery_alert_24
            "NO_BATTERY"   -> R.drawable.ic_battery_unknown_24
            "NOT_CHARGING" -> if (present == false) R.drawable.ic_battery_unknown_24 else R.drawable.ic_battery_24
            else           -> if (present == false) R.drawable.ic_battery_unknown_24 else R.drawable.ic_battery_24
        }

        // Label: prefer %; else show voltage
        val label = when {
            soc != null -> "$soc%"
            volt != null -> String.format(Locale.UK, "%.2f V", volt)
            else -> "— %"
        }

        binding.chipBattery.text = label
        binding.chipBattery.setChipIconResource(iconRes)
    }

    // JSON helpers
    private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

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
            isConnecting = false
            isCaptureOngoing = false
            isPmfiRunning = false
            previewActive = false
            vm.resetToIdle()

            // reset all visible UI surfaces to "fresh"
            resetUiToFreshState()
            resetPmfiUi(hide = true)
            setUiBusy(false)

            // jump user back to start tab
            (activity as? MainActivity)?.goToStartPage()

            toast("Disconnected from Pi")
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

// use the single source of truth
        val busy = isGlobalBusy()
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
    // In ControlFragment (or wherever hookEnvSocket() lives)
    private fun hookEnvSocket() {
        // Pi sends either a JSONObject, a JSON string, or a Map
        PiSocketManager.on("env.update") { payload ->
            try {
                val obj: org.json.JSONObject? = when (payload) {
                    is org.json.JSONObject -> payload
                    is String -> runCatching { org.json.JSONObject(payload) }.getOrNull()
                    is Map<*, *> -> org.json.JSONObject(payload)
                    else -> null
                }

                if (obj == null) {
                    Log.w("ControlFragment", "env.update: unexpected payload type: ${payload?.javaClass?.name}")
                    return@on
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

                if (!isAdded) return@on
                requireActivity().runOnUiThread {
                    renderEnv(latestTempC, latestHumidity, latestEnvIso)
                }
            } catch (t: Throwable) {
                Log.e("ControlFragment", "Handler for 'env.update' failed", t)
            }
        }
    }



    // Pretty-print on the Control tab
    private fun renderEnv(tempC: Double?, rh: Double?, iso: String?) {
        val tLbl = if (tempC == null) "Temp: —" else String.format(Locale.UK, "Temp: %.1f \u00B0C", tempC)
        val hLbl = if (rh == null)    "RH: —"   else String.format(Locale.UK, "RH: %.0f %%", rh)

        binding.topTempChip.text = tLbl
        binding.topHumChip.text  = hLbl

        // keep hidden legacy labels in sync
        binding.envTempText.text     = tLbl
        binding.envHumidityText.text = hLbl
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
        val sw4          = data.optBoolean("sw4", false)
        val busyNow      = data.optBoolean("busy", false)
        val calibrating  = data.optBoolean("calibrating", false)
        val pmfiNow      = data.optBoolean("pmfi_running", false)
        val lastBtn      = data.optString("last_button", "")

        if (!isAdded) return
        requireActivity().runOnUiThread {
            updateConnUi(true)

            if (lastBtn.isNotBlank()) {
                binding.lastPiButtonText.text = "MFI Button Pressed: $lastBtn"
            }

            // reflect stage labels if provided
            val pmfiStage    = data.optString("pmfi_stage", "")
            val pmfiSection  = data.optString("pmfi_section", "")
            if (pmfiStage.isNotBlank()) binding.pmfiStageLabel.text = pmfiStage
            if (pmfiSection.isNotBlank()) {
                binding.pmfiSectionLabel.text = "Current section: $pmfiSection"
            }

            // --- TRUST PI that PMFI might be running ---
            isPmfiRunning = pmfiNow

            // *** IMPORTANT CHANGE STARTS HERE ***

            // Old code:
            // if (!busyNow && !pmfiNow && !calibrating) { isCaptureOngoing = false; ... }

            // New behavior:
            // Only clear our local busy flags if Pi ALSO says idle AND we weren't
            // already in the middle of something we *believe* is running.
            val weThinkBusyLocally =
                isCaptureOngoing ||
                        isCalibratingOngoing ||
                        isPmfiRunning ||
                        (vm.isCalibrating.value == true)

            if (!busyNow && !pmfiNow && !calibrating) {
                // Pi is advertising idle.

                if (!weThinkBusyLocally) {
                    // We're ALSO not in a locally-started run, so yeah, truly idle.
                    isCaptureOngoing = false
                    isCalibratingOngoing = false
                    vm.isCapturing.value = false
                }
                // else: do nothing. We keep our optimistic lock.
            }

            // recompute busy with OR of both views
            val uiBusy = weThinkBusyLocally || busyNow || calibrating || pmfiNow
            setUiBusy(uiBusy)

            // keep preview UI in sync, but only if we're not actively running
            binding.switchCameraPreview.setOnCheckedChangeListener(null)
            binding.switchCameraPreview.isChecked = sw4
            attachPreviewToggleListener()

            val allowPreviewUiChanges = !weThinkBusyLocally && !pmfiNow && (vm.isCalibrating.value != true)
            if (allowPreviewUiChanges) {
                when {
                    sw4 && mode != PreviewMode.LIVE_FEED -> startLivePreview()
                    !sw4 && mode == PreviewMode.LIVE_FEED -> clearPreview()
                }
            }

            // If Pi-side SW2 got pressed (physical button), we still bootstrap AMSI UI
            if (
                lastBtn == "SW2" &&
                !isCaptureOngoing &&
                !isPmfiRunning &&
                (vm.isCalibrating.value != true)
            ) {
                startImageGrid()
                startCaptureUi()
                isCaptureOngoing = true
                vm.capturedBitmaps.value = MutableList(16) { null }
                vm.imageCount.value = 0
                vm.isCapturing.value = true
                clearLiveOnly()
            }
        }

    }

    override fun onAttach(baseContext: Context) {
        // create a context with fontScale forced to 1.0
        val config = android.content.res.Configuration(baseContext.resources.configuration)
        config.fontScale = 1.0f

        val scaledContext = baseContext.createConfigurationContext(config)
        super.onAttach(scaledContext)
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
        // We generate a stable-ish session_id so uploads can tag the run.
        val sessionId = UUID.randomUUID().toString()

        // IMPORTANT:
        // We are NOW sending ini_text (raw multiline INI) as JSON.
        // We are NOT sending ini_b64 anymore because the Pi parser may choke on it
        // for long/complex INIs and respond "ini_b64 decode failed".
        //
        // This matches the Pi route logic:
        //   if ini_text: parse directly
        //   elif ini_b64: base64-decode & parse
        //
        // Sending ini_text avoids the decode path entirely.
        val body = PmfiStartBody(
            ini_text = iniText,
            session_id = sessionId,
            upload_mode = "zip" // Pi ignores this right now but it's fine to include
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = PiApi.api.pmfiStart(body)

                if (resp.isSuccessful) {
                    // We assume success structure like:
                    // { "ok": true, "session_id": "...", "config_id": "...", "plan": {...} }
                    isPmfiRunning = true
                    setUiBusy(true)

                    binding.pmfiStartBtn.text = "PMFI running…"
                    binding.pmfiStageLabel.text = "PMFI started… (sessionId=$sessionId)"

                    // Reset/enable PMFI progress UI immediately so the bars are visible
                    resetPmfiUi(hide = false)
                    showPmfiUi()

                } else {
                    val errBody = resp.errorBody()?.string().orEmpty()
                    toast("PMFI start failed: ${resp.code()} $errBody")

                    // Rollback
                    isPmfiRunning = false
                    setUiBusy(false)
                    setPmfiButtonBusy(false)
                    binding.pmfiStartBtn.text = "Start PMFI"
                }
            } catch (e: Exception) {
                toast("Network error: ${e.localizedMessage}")

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
