package com.example.msiandroidapp.ui.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.databinding.FragmentControlBinding
import com.example.msiandroidapp.network.PiApiService
import com.example.msiandroidapp.network.PiSocketManager
import com.example.msiandroidapp.util.UploadProgressBus
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date

@SuppressLint("MissingPermission")
suspend fun getLastKnownLocationString(context: Context): String {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    val location: Location? = try { fusedLocationClient.lastLocation.await() } catch (_: Exception) { null }
    return if (location != null) "Lat: %.5f, Lon: %.5f".format(location.latitude, location.longitude) else "Unknown"
}

class ControlFragment : Fragment() {

    private var _binding: FragmentControlBinding? = null
    private val binding get() = _binding!!

    private val controlViewModel: ControlViewModel by activityViewModels()

    private var currentIp = ""
    private val connectionCheckHandler = Handler(Looper.getMainLooper())
    private var connectionCheckRunnable: Runnable? = null
    private val refreshIntervalMs = 5000L

    private enum class PreviewMode { NONE, IMAGE_CAPTURE, LIVE_FEED }
    private var currentPreviewMode: PreviewMode = PreviewMode.NONE

    private lateinit var previewContainer: FrameLayout
    private lateinit var previewScrollView: ScrollView
    private var imageGrid: GridLayout? = null
    private val capturedImageViews = mutableListOf<ImageView>()
    private var liveFeedImageView: ImageView? = null
    private var isPiConnected = false
    private var isCaptureOngoing = false

    private var cameraPreviewListener: CompoundButton.OnCheckedChangeListener? = null

    private var calHandlersRegistered = false
    @Volatile private var calSaveInFlight = false

    private val JSON = "application/json; charset=utf-8".toMediaType()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewContainer = view.findViewById(R.id.preview_container)
        previewScrollView = view.findViewById(R.id.preview_scrollview)

        setupSwitchListeners()
        setupButtonListeners()
        setupIpButtonListener()
        setupDisconnectButtonListener() // <-- NEW
        setupShutdownButtonListener()
        setupProgressBar()
        setupProgressObserver()
        setupCalibrationUi()
        setupCalibrationObservers()
        restoreSavedIp()
        updatePiConnectionIndicator(null)

        // Reconnect + request state on view create
        if (currentIp.isNotEmpty()) {
            PiSocketManager.connect(currentIp, ::onPreviewImageEvent, ::onPiStateUpdate)
            hookCalibrationEventsOnce()
            PiSocketManager.emit("get_state", JSONObject())
        }

        // Restore capture UI if needed
        val bitmaps = controlViewModel.capturedBitmaps.value ?: List(16) { null }
        val count = controlViewModel.imageCount.value ?: 0
        val capturing = controlViewModel.isCapturing.value ?: false
        if (capturing || count in 1..15 || bitmaps.any { it != null }) {
            startImagePreview()
            updateImageGrid(bitmaps)
            binding.captureProgressBar.visibility = View.VISIBLE
            binding.captureProgressBar.progress = count
            binding.captureProgressText.visibility = View.VISIBLE
            binding.captureProgressText.text = "Receiving images: $count/16"
        }

        // Observers for capture
        controlViewModel.capturedBitmaps.observe(viewLifecycleOwner) { updateImageGrid(it ?: List(16) { null }) }
        controlViewModel.imageCount.observe(viewLifecycleOwner) { count2 ->
            binding.captureProgressBar.progress = count2
            binding.captureProgressText.text = "Receiving images: $count2/16"
            if (count2 in 1..15) {
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
            } else {
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE
            }
            if (count2 == 16) {
                binding.captureProgressText.text = "All images received!"
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
                val bmaps = controlViewModel.capturedBitmaps.value ?: MutableList(16) { null }
                saveSessionToDatabase(bmaps)
            }
        }
        controlViewModel.isCapturing.observe(viewLifecycleOwner) { capturing2 ->
            if (!capturing2) {
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE
            }
        }
    }

    // =========================
    // Disconnect (does NOT clear IP text or prefs)
    // =========================

    private fun setupDisconnectButtonListener() {
        val btn = binding.buttonDisconnect
        btn.setOnClickListener {
            performDisconnect()
        }
    }

    private fun performDisconnect() {
        // Stop periodic checks
        stopAutoConnectionCheck()

        // Stop previews
        turnOffSW4()
        clearPreview()

        // Reset op flags
        isCaptureOngoing = false
        controlViewModel.isCapturing.value = false
        controlViewModel.imageCount.value = 0
        controlViewModel.isCalibrating.value = false

        // Close socket connection safely
        try {
            PiSocketManager.disconnect()
        } catch (_: Exception) { /* ignore */ }

        // DO NOT clear currentIp or the EditText
        // DO NOT clear saved prefs
        isPiConnected = false
        updatePiConnectionIndicator(false)

        // Keep IP fields editable so user can reconnect
        setUiBusy(false)

        Toast.makeText(requireContext(), "Disconnected from Pi", Toast.LENGTH_SHORT).show()
    }

    // =========================
    // Calibration UI + actions
    // =========================

    private fun setupCalibrationUi() {
        binding.calProgressBar.max = 16
        binding.calProgressBar.progress = 0
        binding.calProgressBar.visibility = View.GONE
        binding.calProgressText.visibility = View.GONE

        binding.buttonCalibrate.setOnClickListener {
            val vmCal = controlViewModel.isCalibrating.value == true
            if (!isPiConnected || isCaptureOngoing || vmCal) {
                Toast.makeText(requireContext(), "Busy or not connected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCalibration()
        }
    }

    private fun setupCalibrationObservers() {
        controlViewModel.isCalibrating.observe(viewLifecycleOwner) { calibrating ->
            val busy = isCaptureOngoing || calibrating || !isPiConnected
            setUiBusy(busy)
            binding.buttonCalibrate.isEnabled = isPiConnected && !isCaptureOngoing && !calibrating
            if (!calibrating) {
                binding.calProgressBar.visibility = View.GONE
                binding.calProgressText.visibility = View.GONE
            }
        }

        controlViewModel.calChannelIndex.observe(viewLifecycleOwner) { idx ->
            val total = controlViewModel.calTotalChannels.value ?: 16
            if (controlViewModel.isCalibrating.value == true) {
                binding.calProgressBar.max = total
                binding.calProgressBar.progress = (idx ?: 0) + 1
                binding.calProgressBar.visibility = View.VISIBLE
                binding.calProgressText.visibility = View.VISIBLE

                val wl = controlViewModel.calWavelengthNm.value
                val ave = controlViewModel.calAverageIntensity.value
                val prevN = controlViewModel.calNormPrev.value
                val newN = controlViewModel.calNormNew.value
                binding.calProgressText.text =
                    "Calibrating: ${(idx ?: 0) + 1}/$total · ${wl ?: "-"}nm · avg=${ave?.let { "%.1f".format(it) } ?: "-"} · norm ${prevN?.let { "%.2f".format(it) } ?: "-"}→${newN?.let { "%.2f".format(it) } ?: "-"}"
            }
        }
    }

    private fun startCalibration() {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
        val roiX = prefs.getFloat("roi_x", 0.30f).toDouble()
        val roiY = prefs.getFloat("roi_y", 0.16f).toDouble()
        val roiW = prefs.getFloat("roi_w", 0.40f).toDouble()
        val roiH = prefs.getFloat("roi_h", 0.65f).toDouble()
        val target = prefs.getFloat("cal_target", 0.80f).toDouble()
        val ledNormsStr = prefs.getString("led_norms_json", null)

        if (currentIp.isEmpty()) {
            Toast.makeText(requireContext(), "Set IP address first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val normsJson = try {
                if (!ledNormsStr.isNullOrEmpty()) JSONArray(ledNormsStr) else null
            } catch (_: Exception) { null }

            val body = JSONObject().apply {
                put("machine", "FB1")
                put("roi_x", roiX); put("roi_y", roiY); put("roi_w", roiW); put("roi_h", roiH)
                put("target_intensity", target)
                if (normsJson != null) put("led_norms", normsJson)
            }

            try {
                val (code, isOk, errText) = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .readTimeout(java.time.Duration.ofSeconds(60))
                        .writeTimeout(java.time.Duration.ofSeconds(15))
                        .build()

                    val req = Request.Builder()
                        .url("http://$currentIp:5000/calibrate")
                        .post(body.toString().toRequestBody(JSON))
                        .build()

                    client.newCall(req).execute().use { resp ->
                        Triple(resp.code, resp.isSuccessful, resp.body?.string()?.take(200))
                    }
                }

                if (isOk) {
                    controlViewModel.startCalibration(totalChannels = 16)
                    showCalUi(start = true)
                    setUiBusy(true)
                    Toast.makeText(requireContext(), "Calibration started", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Calibration failed to start ($code) ${errText ?: ""}".trim(),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: e::class.java.simpleName
                Toast.makeText(requireContext(), "Network error: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCalUi(start: Boolean) {
        if (start) {
            binding.calProgressBar.progress = 0
            binding.calProgressBar.visibility = View.VISIBLE
            binding.calProgressText.visibility = View.VISIBLE
            val total = controlViewModel.calTotalChannels.value ?: 16
            binding.calProgressText.text = "Calibrating: 0/$total"
        } else {
            binding.calProgressBar.visibility = View.GONE
            binding.calProgressText.visibility = View.GONE
        }
    }

    private fun onCalProgress(data: JSONObject) {
        controlViewModel.updateCalibrationProgress(
            channelIndex = data.optInt("channel_index", 0),
            totalChannels = data.optInt("total_channels", 16),
            wavelengthNm = data.optInt("wavelength_nm", -1),
            averageIntensity = data.optDouble("average_intensity", -1.0),
            normPrev = data.optDouble("led_norm_prev", -1.0),
            normNew = data.optDouble("led_norm_new", -1.0)
        )
    }

    private fun onCalComplete(data: JSONObject) {
        if (calSaveInFlight) return
        calSaveInFlight = true

        val normsArr = data.optJSONArray("led_norms")
        val ledNorms: List<Double> =
            if (normsArr != null && normsArr.length() == 16)
                (0 until normsArr.length()).map { i -> normsArr.optDouble(i, 1.0) }
            else
                emptyList()

        if (ledNorms.size == 16) {
            val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
            prefs.edit().putString("led_norms_json", normsArr.toString()).apply()
        }

        lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)

            val roiX = prefs.getFloat("roi_x", 0.30f)
            val roiY = prefs.getFloat("roi_y", 0.16f)
            val roiW = prefs.getFloat("roi_w", 0.40f)
            val roiH = prefs.getFloat("roi_h", 0.65f)
            val target = prefs.getFloat("cal_target", 0.80f)

            val name = "Calibration • " + SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
            val summary = buildString {
                append(if (ledNorms.size == 16) "16 LED norms saved" else "No norms returned")
                append(" • target=").append(String.format("%.2f", target))
                append(" • ROI x=").append(String.format("%.2f", roiX))
                append(", y=").append(String.format("%.2f", roiY))
                append(", w=").append(String.format("%.2f", roiW))
                append(", h=").append(String.format("%.2f", roiH))
            }

            val profile = CalibrationProfile(
                createdAt = System.currentTimeMillis(),
                name = name,
                summary = summary,
                previewPath = null,
                ledNorms = ledNorms
            )

            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(ctx).calibrationDao().insert(profile)
            }

            controlViewModel.completeCalibration(ledNorms)

            if (isAdded) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Calibration complete", Toast.LENGTH_SHORT).show()
                    showCalUi(start = false)
                    setUiBusy(false)
                }
            }
            calSaveInFlight = false
        }
    }

    private fun hookCalibrationEventsOnce() {
        if (calHandlersRegistered) return
        calHandlersRegistered = true

        PiSocketManager.on("cal_progress") { data ->
            (data as? JSONObject)?.let { onCalProgress(it) }
        }
        PiSocketManager.on("cal_complete") { data ->
            (data as? JSONObject)?.let { onCalComplete(it) }
        }
        PiSocketManager.on("cal_error") { data ->
            val msg = (data as? JSONObject)?.optString("message") ?: data.toString()
            onCalError(msg)
        }
    }

    private fun onCalError(message: String) {
        controlViewModel.failCalibration()
        if (!isAdded) return
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "Calibration error: $message", Toast.LENGTH_LONG).show()
            showCalUi(start = false)
            setUiBusy(false)
        }
    }

    // =========================
    // Shutdown
    // =========================

    private fun setupShutdownButtonListener() {
        binding.buttonShutdown.setOnClickListener {
            if (currentIp.isEmpty()) {
                Toast.makeText(requireContext(), "Set IP address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Shutdown System")
                .setMessage("Are you sure you want to shut down the Pi and instrument?")
                .setPositiveButton("Shutdown") { _, _ -> sendShutdownCommand() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun sendShutdownCommand() {
        lifecycleScope.launch {
            try {
                val response = PiApiService.api.shutdownSystem()
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Shutdown command sent", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Network error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================
    // Switches / Buttons
    // =========================

    private fun setupSwitchListeners() {
        cameraPreviewListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            val vmCal = controlViewModel.isCalibrating.value == true
            if (!isPiConnected || isCaptureOngoing || vmCal) {
                binding.switchCameraPreview.isChecked = false
                return@OnCheckedChangeListener
            }
            triggerButton("SW4")
            if (isChecked) startLiveFeedPreview() else clearPreview()
        }
        binding.switchCameraPreview.setOnCheckedChangeListener(cameraPreviewListener)
    }

    private fun setupButtonListeners() {
        binding.button1.setOnClickListener { onSW2Pressed() }
    }

    private fun turnOffSW4() {
        binding.switchCameraPreview.setOnCheckedChangeListener(null)
        if (binding.switchCameraPreview.isChecked) triggerButton("SW4")
        binding.switchCameraPreview.isChecked = false
        binding.switchCameraPreview.setOnCheckedChangeListener(cameraPreviewListener)
    }

    private fun onSW2Pressed() {
        val vmCal = controlViewModel.isCalibrating.value == true
        if (!isPiConnected || isCaptureOngoing || vmCal) {
            Toast.makeText(requireContext(), "Pi not connected or busy", Toast.LENGTH_SHORT).show()
            return
        }
        turnOffSW4()
        if (currentPreviewMode != PreviewMode.IMAGE_CAPTURE) startImagePreview()
        startCaptureProgress()
        isCaptureOngoing = true
        setUiBusy(true)
        triggerButton("SW2")
        controlViewModel.capturedBitmaps.value = MutableList(16) { null }
        controlViewModel.imageCount.value = 0
        controlViewModel.isCapturing.value = true
    }

    // =========================
    // Preview helpers
    // =========================

    private fun startImagePreview() {
        clearPreview()
        currentPreviewMode = PreviewMode.IMAGE_CAPTURE
        previewScrollView.visibility = View.VISIBLE

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        imageGrid = GridLayout(requireContext()).apply {
            rowCount = 4; columnCount = 4
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        capturedImageViews.clear()
        for (i in 0 until 16) {
            val img = ImageView(requireContext()).apply {
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = 220
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
            }
            imageGrid?.addView(img)
            capturedImageViews.add(img)
        }
        container.addView(imageGrid)
        previewContainer.removeAllViews()
        previewContainer.addView(container)
    }

    private fun updateImageGrid(bitmaps: List<Bitmap?>) {
        imageGrid?.let {
            for (i in 0 until 16) {
                val imgView = capturedImageViews.getOrNull(i)
                val bmp = bitmaps.getOrNull(i)
                if (imgView != null) {
                    if (bmp != null) imgView.setImageBitmap(bmp)
                    else imgView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
        }
    }

    private fun startLiveFeedPreview() {
        clearPreview()
        currentPreviewMode = PreviewMode.LIVE_FEED
        previewScrollView.visibility = View.VISIBLE

        liveFeedImageView = ImageView(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewContainer.removeAllViews()
        previewContainer.addView(liveFeedImageView)
    }

    fun decodeBase64ImageToBitmap(imageB64: String): Bitmap? = try {
        val imageBytes = Base64.decode(imageB64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } catch (_: Exception) { null }

    private fun clearPreview() {
        currentPreviewMode = PreviewMode.NONE
        previewScrollView.visibility = View.GONE
        previewContainer.removeAllViews()
        imageGrid = null
        liveFeedImageView = null
        capturedImageViews.clear()
    }

    private fun setupProgressBar() {
        binding.captureProgressBar.max = 16
        binding.captureProgressBar.progress = 0
        binding.captureProgressBar.visibility = View.GONE
        binding.captureProgressText.visibility = View.GONE
    }

    private fun startCaptureProgress() {
        binding.captureProgressBar.progress = 0
        binding.captureProgressBar.visibility = View.VISIBLE
        binding.captureProgressText.text = "Receiving images: 0/16"
        binding.captureProgressText.visibility = View.VISIBLE
    }

    private fun setupProgressObserver() {
        UploadProgressBus.uploadProgress.observe(viewLifecycleOwner) { (_, count) ->
            controlViewModel.imageCount.value = count
            binding.captureProgressBar.progress = count
            binding.captureProgressText.text = "Receiving images: $count/16"
            if (count == 16) {
                controlViewModel.isCapturing.value = false
                binding.captureProgressText.text = "All images received!"
                binding.captureProgressBar.postDelayed({
                    binding.captureProgressBar.visibility = View.GONE
                    binding.captureProgressText.visibility = View.GONE
                    clearPreview()
                    isCaptureOngoing = false
                    setUiBusy(false)
                }, 2000)
            } else {
                controlViewModel.isCapturing.value = true
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
            }
        }
    }

    // =========================
    // Network helpers
    // =========================

    private fun setUiBusy(busy: Boolean) {
        val calibrating = controlViewModel.isCalibrating.value == true
        val capturing = isCaptureOngoing
        val connected = isPiConnected

        // General controls (SW2 capture, SW4 preview, shutdown)
        val controlsEnabled = connected && !busy && !calibrating && !capturing
        binding.switchCameraPreview.isEnabled = controlsEnabled
        binding.button1.isEnabled = controlsEnabled           // SW2
        binding.buttonShutdown.isEnabled = controlsEnabled

        // Calibrate button: only when connected and nothing else is running
        binding.buttonCalibrate.isEnabled = connected && !capturing && !calibrating && !busy

        // Connection UI (Connect/Set IP, Disconnect, and IP text):
        //  - Disable during CAPTURE or CALIBRATION (and any explicit busy)
        val connectionControlsEnabled = !capturing && !calibrating && !busy
        binding.buttonDisconnect.isEnabled = connectionControlsEnabled
        binding.setIpButton.isEnabled = connectionControlsEnabled
        binding.ipAddressInput.isEnabled = connectionControlsEnabled
    }




    private fun triggerButton(buttonId: String) {
        if (currentIp.isEmpty()) {
            Toast.makeText(requireContext(), "Set IP address first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val response = PiApiService.api.triggerScript(buttonId)
                if (!response.isSuccessful) {
                    Toast.makeText(requireContext(), "Failed to trigger $buttonId", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e !is SocketTimeoutException) {
                    Toast.makeText(requireContext(), "Network error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupIpButtonListener() {
        binding.setIpButton.setOnClickListener {
            val enteredIp = binding.ipAddressInput.text.toString().trim()
            if (enteredIp.isNotEmpty()) {
                currentIp = enteredIp
                PiApiService.setBaseUrl(enteredIp)
                saveIpToPrefs(enteredIp)
                checkPiConnection(enteredIp)

                PiSocketManager.connect(currentIp, ::onPreviewImageEvent, ::onPiStateUpdate)
                hookCalibrationEventsOnce()
            } else {
                updatePiConnectionIndicator(null)
            }
        }
    }

    private fun saveIpToPrefs(ip: String) {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
        prefs.edit().putString("server_ip", ip).apply()
    }

    private fun restoreSavedIp() {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("server_ip", "") ?: ""
        if (savedIp.isNotEmpty()) {
            currentIp = savedIp
            binding.ipAddressInput.setText(savedIp)
            PiApiService.setBaseUrl(savedIp)
            PiSocketManager.connect(currentIp, ::onPreviewImageEvent, ::onPiStateUpdate)
            hookCalibrationEventsOnce()
        }
    }

    private fun checkPiConnection(ip: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url("http://$ip:5000/status").get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (isAdded) requireActivity().runOnUiThread { updatePiConnectionIndicator(false) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (isAdded) requireActivity().runOnUiThread { updatePiConnectionIndicator(response.isSuccessful) }
            }
        })
    }

    private fun updatePiConnectionIndicator(connected: Boolean?) {
        val statusDrawable = when (connected) {
            true -> R.drawable.circle_green
            false -> R.drawable.circle_red
            null -> R.drawable.circle_grey
        }
        binding.piConnectionStatus.text = when (connected) {
            true -> "Status: Connected"
            false -> "Status: Not Connected"
            null -> "Status: Unknown"
        }
        binding.piConnectionDot.background =
            ContextCompat.getDrawable(requireContext(), statusDrawable)

        isPiConnected = (connected == true)

        val operationBusy = isCaptureOngoing || (controlViewModel.isCalibrating.value == true)
        setUiBusy(operationBusy)
    }

    // =========================
    // Lifecycle
    // =========================

    override fun onDestroyView() {
        _binding = null
        clearPreview()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        startAutoConnectionCheck()
        if (currentIp.isNotEmpty()) {
            PiSocketManager.emit("get_state", JSONObject())
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoConnectionCheck()
    }

    private fun startAutoConnectionCheck() {
        stopAutoConnectionCheck()
        connectionCheckRunnable = object : Runnable {
            override fun run() {
                if (currentIp.isNotEmpty()) checkPiConnection(currentIp) else updatePiConnectionIndicator(null)
                connectionCheckHandler.postDelayed(this, refreshIntervalMs)
            }
        }
        connectionCheckHandler.post(connectionCheckRunnable!!)
    }

    private fun stopAutoConnectionCheck() {
        connectionCheckRunnable?.let { connectionCheckHandler.removeCallbacks(it) }
    }

    // =========================
    // Socket handlers
    // =========================

    private fun onPreviewImageEvent(data: JSONObject, bitmap: Bitmap) {
        Log.d("ControlFragment", "onPreviewImageEvent called. Bitmap: $bitmap, Size: ${bitmap.width}x${bitmap.height}")
        if (currentPreviewMode == PreviewMode.LIVE_FEED) {
            requireActivity().runOnUiThread { liveFeedImageView?.setImageBitmap(bitmap) }
        }
        val idx = data.optInt("index", -1)
        if (idx in 0 until 16) {
            requireActivity().runOnUiThread { controlViewModel.addBitmap(idx, bitmap) }
        }
    }

    private fun onPiStateUpdate(data: JSONObject) {
        Log.d("ControlFragment", "onPiStateUpdate: $data")
        val binding = _binding ?: return
        requireActivity().runOnUiThread {
            val sw4 = data.optBoolean("sw4", false)
            val busyFromPi = data.optBoolean("busy", false)
            val calFromPi = data.optBoolean("calibrating", false)
            val lastButton = data.optString("last_button", "")

            controlViewModel.isCalibrating.value =
                calFromPi || (controlViewModel.isCalibrating.value == true)

            binding.switchCameraPreview.setOnCheckedChangeListener(null)
            binding.switchCameraPreview.isChecked = sw4
            binding.switchCameraPreview.setOnCheckedChangeListener(cameraPreviewListener)

            val shouldDisableUi = isCaptureOngoing || busyFromPi || (controlViewModel.isCalibrating.value == true)
            setUiBusy(shouldDisableUi)

            if (!lastButton.isNullOrBlank()) binding.lastPiButtonText.text = "Pi Button Pressed: $lastButton"

            if (sw4 && currentPreviewMode != PreviewMode.LIVE_FEED) startLiveFeedPreview()
            else if (!sw4 && currentPreviewMode == PreviewMode.LIVE_FEED) clearPreview()

            if (lastButton == "SW2" && !isCaptureOngoing) {
                turnOffSW4()
                if (currentPreviewMode != PreviewMode.IMAGE_CAPTURE) startImagePreview()
                startCaptureProgress()
                isCaptureOngoing = true
                setUiBusy(true)
                controlViewModel.capturedBitmaps.value = MutableList(16) { null }
                controlViewModel.imageCount.value = 0
                controlViewModel.isCapturing.value = true
            }
        }
    }

    // =========================
    // Save session
    // =========================

    private fun saveSessionToDatabase(bitmaps: List<Bitmap?>) {
        lifecycleScope.launch {
            val context = requireContext().applicationContext
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            val imagePaths = mutableListOf<String>()

            bitmaps.forEachIndexed { idx, bmp ->
                if (bmp != null) {
                    val filename = "session_${System.currentTimeMillis()}_img_$idx.jpg"
                    val file = context.getFileStreamPath(filename)
                    file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                    imagePaths.add(file.absolutePath)
                }
            }

            val location = getLastKnownLocationString(context)
            val session = Session(timestamp = timestamp, location = location, imagePaths = imagePaths)
            AppDatabase.getDatabase(context).sessionDao().insert(session)

            withContext(Dispatchers.Main) {
                binding.captureProgressText.text = "All images received!"
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
            }

            delay(2000)

            withContext(Dispatchers.Main) {
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE
                clearPreview()
                isCaptureOngoing = false
                setUiBusy(false)
                controlViewModel.isCapturing.value = false
                controlViewModel.imageCount.value = 0
                controlViewModel.capturedBitmaps.value = MutableList(16) { null }
            }
        }
    }
}
