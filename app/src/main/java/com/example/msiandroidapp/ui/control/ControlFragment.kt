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
    private var calInfoLine: String = ""
    private var calExpectedImages = 16
    private var calTotalChannels = 16

    // Track preview state mirrored from server
    private var previewActive = false
    private var previewRequestedState: Boolean? = null
    private var previewRequestPendingUntil: Long = 0L
    private val previewRequestAckWindowMs = 900L
    // Remember preview state before AMSI so we can restore warming afterwards
    private var wasPreviewOnBeforeAmsi = false

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
                isCaptureOngoing = false
                vm.isCapturing.value = false
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE

                clearPreview()
                setUiBusy(false)
                if (wasPreviewOnBeforeAmsi) kickPreviewResume()
                wasPreviewOnBeforeAmsi = false
                toast("Capture aborted")
            }
        }


        // --- Calibration progress / complete / error from Pi ---
        // We'll inline what used to be hookCalibrationSocket(), but with the fixed cleanup-on-error
        PiSocketManager.on("cal_plan") { payload ->
            val j = payload as? JSONObject ?: return@on
            val channels = j.optInt("channels", 16)
            val darkExpected = j.optInt("dark_images_expected", 0)
            val totalExpected = j.optInt("images_expected", channels + darkExpected)
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                calTotalChannels = channels
                vm.startCalibration(totalChannels = channels)
                calDarkFrameSeen = darkExpected > 0
                calExtraImagesExpected = darkExpected.coerceAtLeast(0)
                calExpectedImages = totalExpected.coerceAtLeast(channels)
                calDarkImagesUploaded = 0
                binding.calProgressBar.max = calExpectedImages
                if (binding.calProgressBar.visibility != View.VISIBLE) {
                    binding.calProgressBar.visibility = View.VISIBLE
                }
                if (binding.calProgressText.visibility != View.VISIBLE) {
                    binding.calProgressText.visibility = View.VISIBLE
                }
                binding.calProgressText.text = "Calibrating: 0/${binding.calProgressBar.max}"
            }
        }

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
                // Ensure we're marked busy during cal, in case this was Pi-initiated
                isCalibratingOngoing = true
                vm.isCalibrating.value = true
                setUiBusy(true)

                refreshCalExpectedImages()
                binding.calProgressBar.visibility = View.VISIBLE
                binding.calProgressText.visibility = View.VISIBLE

                val wl  = vm.calWavelengthNm.value
                val ave = vm.calAverageIntensity.value
                val p   = vm.calNormPrev.value
                val n   = vm.calNormNew.value

                calInfoLine =
                    "${wl ?: "-"}nm · avg=${ave?.let { String.format(Locale.US, "%.1f", it) } ?: "-"} · " +
                            "norm ${p?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}→" +
                            "${n?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}"
                updateCalProgressText()
            }
        }

        PiSocketManager.on("cal_uploaded") { payload ->
            val j = payload as? JSONObject ?: return@on
            val imageType = j.optString("image_type", "")
            val fileName = j.optString("file", "")
            val isDark = imageType.equals("dark", true) || fileName.contains("dark", true)
            if (isDark) calDarkFrameSeen = true
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                // Count every uploaded calibration image (dark + final LED)
                calDarkImagesUploaded += 1
                refreshCalExpectedImages()
                binding.calProgressBar.progress =
                    (binding.calProgressBar.progress + 1).coerceAtMost(binding.calProgressBar.max)
                updateCalProgressText()
            }
        }

        // cal_complete
        PiSocketManager.on("cal_complete") { payload ->
            val j = payload as? JSONObject
            val norms = j?.optJSONArray("led_norms")
            if (norms != null && norms.length() == 16) {
                val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
                prefs.edit().putString("led_norms_json", norms.toString()).apply()
            }
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                val images = j?.optInt("images", 16) ?: 16
                val totalChannels = vm.calTotalChannels.value ?: calTotalChannels
                if (images > totalChannels) {
                    calDarkFrameSeen = true
                    calExtraImagesExpected = (images - totalChannels).coerceAtLeast(0)
                    refreshCalExpectedImages()
                }
                binding.calProgressBar.progress =
                    binding.calProgressBar.progress.coerceAtMost(binding.calProgressBar.max)
                calCooldownUntil = now() + 1_500L
                endCalibrationUi("Calibration complete")
                // Nudge a fresh state from Pi, but UI is already unlocked
                PiSocketManager.emit("get_state", JSONObject())
            }
        }

// cal_error
        PiSocketManager.on("cal_error") { _ ->
            if (!isAdded) return@on
            requireActivity().runOnUiThread {
                vm.failCalibration()
                calCooldownUntil = now() + 1_500L
                endCalibrationUi("Calibration aborted")
                PiSocketManager.emit("get_state", JSONObject())
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
        if (now() < calStartGraceUntil) return true
        val inCalCooldown = now() < calCooldownUntil
        val calFlag = (isCalibratingOngoing || vm.isCalibrating.value == true) && !inCalCooldown
        return isCaptureOngoing || isPmfiRunning || calFlag
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
        wasPreviewOnBeforeAmsi = false

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
        binding.calProgressBar.progress = 0
        binding.calProgressBar.visibility = View.GONE
        binding.calProgressText.text = ""
        binding.calProgressText.visibility = View.GONE

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


        // AMSI (SW2)
        binding.buttonStartAmsi.setOnClickListener {
            if (!isPiConnected) { toast("Pi not connected"); return@setOnClickListener }
            val gotLock = tryBeginBusy("amsi"); if (!gotLock) { toast("Busy"); return@setOnClickListener }

            viewLifecycleOwner.lifecycleScope.launch {
                // 1) Remember if preview (warming) was ON before we stop it
                wasPreviewOnBeforeAmsi = lastSw4FromServer || binding.switchCameraPreview.isChecked || previewActive

                // 2) Stop preview in background (server will hard-stop before capture)
                ensurePreviewOffAsync(1500)
                val ok = true
                if (!ok && lastSw4FromServer) {
                    // Could not turn preview off — fail fast & unlock
                    isCaptureOngoing = false
                    vm.isCapturing.value = false
                    setUiBusy(false)
                    toast("Preview did not stop — try again")
                    return@launch
                }

                // 3) Start AMSI UI
                vm.isCapturing.value = true
                startImageGrid()
                startCaptureUi()
                vm.capturedBitmaps.value = MutableList(16) { null }
                vm.imageCount.value = 0
                binding.buttonStartAmsi.isEnabled = false
                binding.buttonStartAmsi.alpha = 0.4f

                // 4) Trigger SW2 (AMSI start)
                runCatching { triggerButton("SW2") }.onFailure { e ->
                    toast("Failed to start capture: ${e.localizedMessage}")
                    isCaptureOngoing = false
                    vm.isCapturing.value = false
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
            vm.startCalibration(totalChannels = 16)
            showCalUi(true)
            binding.buttonCalibrate.isEnabled = false
            binding.buttonCalibrate.alpha = 0.4f

            viewLifecycleOwner.lifecycleScope.launch {
                ensurePreviewOffAsync(1500)
                val ok = true
                if (!ok && lastSw4FromServer) {
                    isCalibratingOngoing = false
                    vm.isCalibrating.value = false
                    setUiBusy(false)
                    toast("Preview did not stop — try again")
                    return@launch
                }

                triggerButton("SW3") // end/unlock via cal_complete / cal_error
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
                ensurePreviewOffAsync(1500)
                val ok = true
                if (!ok && lastSw4FromServer) {
                    isPmfiRunning = false
                    setUiBusy(false)
                    setPmfiButtonBusy(false)
                    binding.pmfiStartBtn.text = "Start PMFI"
                    toast("Preview did not stop — try again")
                    return@launch
                }
                startPmfi(iniText)
            }
        }





        // Shutdown
        binding.buttonShutdown.setOnClickListener {
            if (currentIp.isEmpty()) { toast("Set IP address first"); return@setOnClickListener }
            if (isPmfiRunning) { toast("PMFI running – wait for completion"); return@setOnClickListener }
            AlertDialog.Builder(requireContext())
                .setTitle("Shutdown System")
                .setMessage("Are you sure you want to shut down the Pi and instrument?")
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
        binding.captureProgressBar.max = 16
        binding.captureProgressBar.progress = 0
        binding.captureProgressBar.visibility = View.GONE
        binding.captureProgressText.visibility = View.GONE
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
                delay(400)
                binding.switchCameraPreview.isEnabled = isPiConnected && !isGlobalBusy()
                binding.switchCameraPreview.alpha =
                    if (binding.switchCameraPreview.isEnabled) 1f else 0.4f
                toggleInFlight = false
            }
        }
    }

// ADD — robust preview control + waiting for Pi ack

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

    private fun requestPreviewSet(targetOn: Boolean) {
        if (!targetOn) clearPreview() else startLivePreview()
        previewActive = targetOn

        if (lastSw4FromServer == targetOn) {
            clearPreviewRequest()
            return
        }

        markPreviewRequest(targetOn)
        runCatching { triggerButton("SW4") }
    }

    private suspend fun ensurePreviewSet(targetOn: Boolean, timeoutMs: Long = 2_000L): Boolean {
        // Local canvas update for instant UX
        if (!targetOn) clearPreview() else startLivePreview()

        // If server already in target state, we're done
        if (lastSw4FromServer == targetOn) {
            previewActive = targetOn
            clearPreviewRequest()
            return true
        }

        markPreviewRequest(targetOn)
        runCatching { triggerButton("SW4") }

        val start = System.currentTimeMillis()
        while ((System.currentTimeMillis() - start) < timeoutMs) {
            if (lastSw4FromServer == targetOn) {
                previewActive = targetOn
                clearPreviewRequest()
                return true
            }
            delay(40)
        }

        // Timed out — fall back to local truth (off is safest)
        if (!targetOn) {
            previewActive = false
            clearPreview()
        }
        if (!isPreviewRequestPending()) {
            clearPreviewRequest()
        }
        return lastSw4FromServer == targetOn
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

    private fun observeUploadProgress() {
        var lastHandledRunId: String? = null

        UploadProgressBus.uploadProgress.observe(viewLifecycleOwner) { (sessionId, count) ->
            // Ignore replayed/stale emissions from previous app sessions
            if (sessionId == lastHandledRunId && count == 16) return@observe

            // Ignore if we’re not currently doing AMSI
            if (isPmfiRunning || (vm.isCalibrating.value == true)) return@observe

            // First image → mark as active run
            if (count == 1) currentAmsiRunId = sessionId

            Log.d(TAG, "Upload progress $sessionId : $count")
            vm.imageCount.value = count
            binding.captureProgressBar.progress = count
            binding.captureProgressText.text = "Receiving images: $count/16"

            when {
                count in 1..15 -> {
                    vm.isCapturing.value = true
                    binding.captureProgressBar.visibility = View.VISIBLE
                    binding.captureProgressText.visibility = View.VISIBLE
                }

                count == 16 -> {
                    lastHandledRunId = sessionId
                    vm.isCapturing.value = false
                    binding.captureProgressText.text = "All images received!"

                    viewLifecycleOwner.lifecycleScope.launch {
                        val id = currentAmsiRunId ?: sessionId
                        val humanSize = resolveAmsiHumanSize(id)
                        // Only show toast if this run actually happened during this session
                        if (isAdded && currentAmsiRunId == sessionId) {
                            toast(if (humanSize != null) "AMSI saved $humanSize" else "AMSI saved")
                        }

                        delay(1500)
                        if (!isAdded) return@launch
                        binding.captureProgressBar.visibility = View.GONE
                        binding.captureProgressText.visibility = View.GONE
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
    private val batteryLowThresholdPct = 20

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
        val current = snap.optDoubleOrNull("current_a")

        val isLow = soc != null && soc < batteryLowThresholdPct
        val isPluggedIn = state == "NOT_CHARGING" && current != null && current <= 0.03
        val isOnBattery = state == "NOT_CHARGING" && current != null && current > 0.03

        // Choose icon
        val iconRes = when (state) {
            "CHARGING"   -> R.drawable.ic_battery_charging_24
            "FAULT"      -> R.drawable.ic_battery_alert_24
            "NO_BATTERY" -> R.drawable.ic_battery_unknown_24
            "NOT_CHARGING" -> when {
                present == false -> R.drawable.ic_battery_unknown_24
                isLow -> R.drawable.ic_battery_alert_24
                isPluggedIn -> R.drawable.ic_battery_charging_24
                isOnBattery -> R.drawable.ic_battery_24
                else -> R.drawable.ic_battery_24
            }
            else -> when {
                present == false -> R.drawable.ic_battery_unknown_24
                isLow -> R.drawable.ic_battery_alert_24
                else -> R.drawable.ic_battery_24
            }
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
            wasPreviewOnBeforeAmsi = false

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
        // ---- 1) Parse the snapshot (server is source of truth) ----
        val serverSw4   = data.optBoolean("sw4", false)
        var sw4         = serverSw4
        var busyNow     = data.optBoolean("busy", false)
        var calNow      = data.optBoolean("calibrating", false)
        val pmfiNow     = data.optBoolean("pmfi_running", false)
        val lastBtn     = data.optString("last_button", "")
        val pmfiStage   = data.optString("pmfi_stage", "")
        val pmfiSection = data.optString("pmfi_section", "")

        if (!isAdded) return
        requireActivity().runOnUiThread {
            // ---- 2) Heartbeat + cosmetic labels ----
            updateConnUi(true)
            if (lastBtn.isNotBlank()) {
                binding.lastPiButtonText.text = "MFi Button Pressed: $lastBtn"
            }
            if (pmfiStage.isNotBlank()) binding.pmfiStageLabel.text = pmfiStage
            if (pmfiSection.isNotBlank()) binding.pmfiSectionLabel.text = "Current section: $pmfiSection"

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
                startImageGrid()
                startCaptureUi()
                vm.capturedBitmaps.value = MutableList(16) { null }
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
                vm.startCalibration(totalChannels = 16)
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
                vm.startCalibration(totalChannels = 16)
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
                return@runOnUiThread
            }
            if (!busyNow && !pmfiNow && !calNow) {
                // Finish cal UI if it was showing
                if (binding.calProgressBar.visibility == View.VISIBLE || binding.calProgressText.visibility == View.VISIBLE || isCalibratingOngoing || (vm.isCalibrating.value == true)) {
                    endCalibrationUi(null)
                }
                // If AMSI flags were left on but uploads are done, unlock
                if (isCaptureOngoing && (vm.imageCount.value ?: 0) >= 16) {
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
    // Robust AMSI size resolver with short retry window (up to ~2s).
    private suspend fun resolveAmsiHumanSize(runId: String): String? = withContext(Dispatchers.IO) {
        // Try a few times because DB/file I/O can lag the 16th image event
        repeat(8) { attempt ->
            try {
                val db  = com.example.msiandroidapp.data.AppDatabase.getDatabase(requireContext())
                val dao = db.sessionDao()
                val s   = dao.findByRunId(runId)

                // 1) Prefer parent of first persisted image path (most reliable)
                val fromDbDir: java.io.File? = s?.imagePaths?.firstOrNull()?.let { path ->
                    java.io.File(path).parentFile?.takeIf { it.exists() }
                }

                // 2) Fallback: app-scoped sessions folder (scoped storage-safe)
                //    e.g. /storage/emulated/0/Android/data/<pkg>/files/MSI_App/Sessions/<runId>
                val appFiles = requireContext().getExternalFilesDir(null)
                val fallbackDir = java.io.File(appFiles, "MSI_App/Sessions/$runId")
                    .takeIf { it.exists() }

                val dir = fromDbDir ?: fallbackDir
                if (dir != null) {
                    val totalBytes = dir.walkTopDown()
                        .filter { it.isFile }
                        .map { it.length() }
                        .sum()
                    val mb = totalBytes / (1024.0 * 1024.0)
                    return@withContext String.format(Locale.getDefault(), "%.1f MB", mb)
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveAmsiHumanSize attempt $attempt failed: ${e.message}")
            }
            // Backoff a bit then try again
            Thread.sleep(250)
        }
        null
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
        if (mode == PreviewMode.LIVE_FEED && liveImage != null) return
        mode = PreviewMode.LIVE_FEED
        previewActive = true
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
        previewActive = false
        previewScroll.visibility = View.GONE
        previewContainer.removeAllViews()
        liveImage = null
        grid = null
        gridImages.clear()
    }


    private fun showCalUi(show: Boolean) {
        if (show) {
            refreshCalExpectedImages()
            binding.calProgressBar.progress = 0
            binding.calProgressBar.visibility = View.VISIBLE
            binding.calProgressText.visibility = View.VISIBLE
            updateCalProgressText()
        } else {
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
        calInfoLine = ""
        calTotalChannels = vm.calTotalChannels.value ?: 16
        calExpectedImages = 16
        refreshCalExpectedImages()
    }

    private fun refreshCalExpectedImages() {
        val totalChannels = vm.calTotalChannels.value ?: calTotalChannels
        calExpectedImages = if (calDarkFrameSeen) {
            totalChannels + calExtraImagesExpected
        } else {
            totalChannels
        }
        binding.calProgressBar.max = calExpectedImages
        if (binding.calProgressBar.progress > calExpectedImages) {
            binding.calProgressBar.progress = calExpectedImages
        }
    }

    private fun updateCalProgressText() {
        val done = binding.calProgressBar.progress
        val total = binding.calProgressBar.max
        val info = if (calInfoLine.isNotBlank()) " · $calInfoLine" else ""
        binding.calProgressText.text = "Calibrating: $done/$total$info"
    }
    // SUSPEND: turn preview off on the Pi and locally, wait briefly for ack.
    private suspend fun ensurePreviewOff(timeoutMs: Long = 2_000L) {
        killPreviewIfNeededAndWait(timeoutMs)
    }


    // NON-SUSPEND wrapper: call from non-suspend contexts (e.g. socket callback)
    private fun ensurePreviewOffAsync(timeoutMs: Long = 2_000L) {
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
