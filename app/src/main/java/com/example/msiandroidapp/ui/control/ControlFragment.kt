package com.example.msiandroidapp.ui.control

import com.example.msiandroidapp.ui.control.ControlViewModel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.msiandroidapp.R
import com.example.msiandroidapp.databinding.FragmentControlBinding
import com.example.msiandroidapp.network.PiSocketManager
import com.example.msiandroidapp.network.PiApiService
import com.example.msiandroidapp.util.UploadProgressBus
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import androidx.appcompat.app.AlertDialog
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.data.AppDatabase
import android.annotation.SuppressLint
import android.location.Location
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import android.content.Context
import kotlinx.coroutines.delay

@SuppressLint("MissingPermission")
suspend fun getLastKnownLocationString(context: Context): String {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    val location: Location? = try {
        fusedLocationClient.lastLocation.await()
    } catch (e: Exception) {
        null
    }
    return if (location != null) {
        "Lat: %.5f, Lon: %.5f".format(location.latitude, location.longitude)
    } else {
        "Unknown"
    }
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

    private var ledWarmingListener: CompoundButton.OnCheckedChangeListener? = null
    private var cameraPreviewListener: CompoundButton.OnCheckedChangeListener? = null

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
        setupShutdownButtonListener()
        setupProgressBar()
        setupProgressObserver()
        restoreSavedIp()
        updatePiConnectionIndicator(null)

        // Reconnect and request Pi state (do this every time view is created)
        if (currentIp.isNotEmpty()) {
            PiSocketManager.connect(currentIp, ::onPreviewImageEvent, ::onPiStateUpdate)
            PiSocketManager.emit("get_state", JSONObject())
        }

        // If a capture is ongoing, restore the preview grid
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

        // Usual observers
        controlViewModel.capturedBitmaps.observe(viewLifecycleOwner) { bitmaps ->
            updateImageGrid(bitmaps ?: List(16) { null })
        }
        controlViewModel.imageCount.observe(viewLifecycleOwner) { count ->
            binding.captureProgressBar.progress = count
            binding.captureProgressText.text = "Receiving images: $count/16"
            if (count in 1..15) {
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
            } else {
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE
            }
            if (count == 16) {
                binding.captureProgressText.text = "All images received!"
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
                val bitmaps = controlViewModel.capturedBitmaps.value ?: MutableList(16) { null }
                saveSessionToDatabase(bitmaps)
            }
        }
        controlViewModel.isCapturing.observe(viewLifecycleOwner) { capturing ->
            if (!capturing) {
                binding.captureProgressBar.visibility = View.GONE
                binding.captureProgressText.visibility = View.GONE
            }
        }
    }



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

    private fun setupSwitchListeners() {
        ledWarmingListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            if (!isPiConnected || isCaptureOngoing) {
                binding.switchLedWarming.isChecked = false
                return@OnCheckedChangeListener
            }
            triggerButton("SW3")
        }
        cameraPreviewListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (!isPiConnected || isCaptureOngoing) {
                binding.switchCameraPreview.isChecked = false
                return@OnCheckedChangeListener
            }
            triggerButton("SW4")
            if (isChecked) startLiveFeedPreview() else clearPreview()
        }
        binding.switchLedWarming.setOnCheckedChangeListener(ledWarmingListener)
        binding.switchCameraPreview.setOnCheckedChangeListener(cameraPreviewListener)
    }

    private fun setupButtonListeners() {
        binding.button1.setOnClickListener { onSW2Pressed() }
    }

    private fun turnOffSW3andSW4() {
        binding.switchLedWarming.setOnCheckedChangeListener(null)
        binding.switchCameraPreview.setOnCheckedChangeListener(null)
        if (binding.switchLedWarming.isChecked) triggerButton("SW3")
        if (binding.switchCameraPreview.isChecked) triggerButton("SW4")
        binding.switchLedWarming.isChecked = false
        binding.switchCameraPreview.isChecked = false
        binding.switchLedWarming.setOnCheckedChangeListener(ledWarmingListener)
        binding.switchCameraPreview.setOnCheckedChangeListener(cameraPreviewListener)
    }

    private fun onSW2Pressed() {
        if (!isPiConnected || isCaptureOngoing) {
            Toast.makeText(requireContext(), "Pi is not connected or capture in progress", Toast.LENGTH_SHORT).show()
            return
        }
        turnOffSW3andSW4()
        if (currentPreviewMode != PreviewMode.IMAGE_CAPTURE) {
            startImagePreview()
        }
        startCaptureProgress()
        isCaptureOngoing = true
        setUiBusy(true)
        triggerButton("SW2")
        // Reset progress in ViewModel
        controlViewModel.capturedBitmaps.value = MutableList(16) { null }
        controlViewModel.imageCount.value = 0
        controlViewModel.isCapturing.value = true
    }

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
            rowCount = 4
            columnCount = 4
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        capturedImageViews.clear()
        for (i in 0 until 16) {
            val img = ImageView(requireContext()).apply {
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 220
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
        imageGrid?.let { grid ->
            for (i in 0 until 16) {
                val imgView = capturedImageViews.getOrNull(i)
                val bmp = bitmaps.getOrNull(i)
                if (imgView != null) {
                    if (bmp != null) {
                        imgView.setImageBitmap(bmp)
                    } else {
                        imgView.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
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
    fun decodeBase64ImageToBitmap(imageB64: String): Bitmap? {
        return try {
            val imageBytes = Base64.decode(imageB64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            null
        }
    }
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

    private fun setUiBusy(busy: Boolean) {
        // Controls that depend on Pi connection
        binding.switchLedWarming.isEnabled = !busy && isPiConnected
        binding.switchCameraPreview.isEnabled = !busy && isPiConnected
        binding.button1.isEnabled = !busy && isPiConnected
        binding.buttonShutdown.isEnabled = !busy && isPiConnected

        // Controls for entering IP are always enabled unless BUSY (e.g. image capture is running)
        binding.setIpButton.isEnabled = !busy
        binding.ipAddressInput.isEnabled = !busy
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
                // Use singleton manager!
                PiSocketManager.connect(currentIp, ::onPreviewImageEvent, ::onPiStateUpdate)
            } else {
                updatePiConnectionIndicator(null)
            }
        }
    }

    private fun saveIpToPrefs(ip: String) {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("server_ip", ip).apply()
    }

    private fun restoreSavedIp() {
        val prefs = requireActivity().getSharedPreferences("APP_SETTINGS", android.content.Context.MODE_PRIVATE)
        val savedIp = prefs.getString("server_ip", "") ?: ""
        if (savedIp.isNotEmpty()) {
            currentIp = savedIp
            binding.ipAddressInput.setText(savedIp)
            PiApiService.setBaseUrl(savedIp)
            PiSocketManager.connect(currentIp, ::onPreviewImageEvent, ::onPiStateUpdate)
        }
    }

    private fun checkPiConnection(ip: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url("http://$ip:5000/status").get().build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Don't call requireActivity() if fragment is not attached!
                if (isAdded) {
                    requireActivity().runOnUiThread { updatePiConnectionIndicator(false) }
                }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (isAdded) {
                    requireActivity().runOnUiThread { updatePiConnectionIndicator(response.isSuccessful) }
                }
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
        binding.piConnectionDot.background = ContextCompat.getDrawable(requireContext(), statusDrawable)

        isPiConnected = connected == true

        // Only disable UI if capture is ongoing, NOT if disconnected!
        setUiBusy(isCaptureOngoing)
    }


    override fun onDestroyView() {
        _binding = null
        // Do NOT disconnect the Pi socket here!
        clearPreview()
        super.onDestroyView()
    }

    private fun onPreviewImageEvent(data: JSONObject, bitmap: Bitmap) {
        Log.d("ControlFragment", "onPreviewImageEvent called. Bitmap: $bitmap, Size: ${bitmap.width}x${bitmap.height}")

        if (currentPreviewMode == PreviewMode.LIVE_FEED) {
            requireActivity().runOnUiThread {
                liveFeedImageView?.setImageBitmap(bitmap)
            }
        }

        val idx = data.optInt("index", -1)
        if (idx in 0 until 16) {
            // If your grid updates Bitmaps, do it on UI thread too:
            requireActivity().runOnUiThread {
                controlViewModel.addBitmap(idx, bitmap)
            }
        }
    }




    private fun onPiStateUpdate(data: JSONObject) {
        Log.d("ControlFragment", "onPiStateUpdate: $data")
        val binding = _binding ?: return
        requireActivity().runOnUiThread {
            val sw3 = data.optBoolean("sw3", false)
            val sw4 = data.optBoolean("sw4", false)
            val busyFromPi = data.optBoolean("busy", false)
            val lastButton = data.optString("last_button", "")

            // Prevent triggering listeners while updating programmatically!
            binding.switchLedWarming.setOnCheckedChangeListener(null)
            binding.switchCameraPreview.setOnCheckedChangeListener(null)
            binding.switchLedWarming.isChecked = sw3
            binding.switchCameraPreview.isChecked = sw4
            binding.switchLedWarming.setOnCheckedChangeListener(ledWarmingListener)
            binding.switchCameraPreview.setOnCheckedChangeListener(cameraPreviewListener)

            val shouldDisableUi = isCaptureOngoing || busyFromPi || !isPiConnected
            setUiBusy(shouldDisableUi)

            if (!lastButton.isNullOrBlank()) {
                binding.lastPiButtonText.text = "Pi Button Pressed: $lastButton"
            }

            if (sw4 && currentPreviewMode != PreviewMode.LIVE_FEED) {
                startLiveFeedPreview()
            } else if (!sw4 && currentPreviewMode == PreviewMode.LIVE_FEED) {
                clearPreview()
            }

            if (lastButton == "SW2" && !isCaptureOngoing) {
                turnOffSW3andSW4()
                if (currentPreviewMode != PreviewMode.IMAGE_CAPTURE) {
                    startImagePreview()
                }
                startCaptureProgress()
                isCaptureOngoing = true
                setUiBusy(true)
                controlViewModel.capturedBitmaps.value = MutableList(16) { null }
                controlViewModel.imageCount.value = 0
                controlViewModel.isCapturing.value = true
            }
        }
    }


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

            val session = Session(
                timestamp = timestamp,
                location = location,
                imagePaths = imagePaths
            )
            AppDatabase.getDatabase(context).sessionDao().insert(session)

            requireActivity().runOnUiThread {
                binding.captureProgressText.text = "All images received!"
                binding.captureProgressBar.visibility = View.VISIBLE
                binding.captureProgressText.visibility = View.VISIBLE
            }
            delay(2000)
            requireActivity().runOnUiThread {
                val binding = _binding ?: return@runOnUiThread
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
                if (currentIp.isNotEmpty()) {
                    checkPiConnection(currentIp)
                } else {
                    updatePiConnectionIndicator(null)
                }
                connectionCheckHandler.postDelayed(this, refreshIntervalMs)
            }
        }
        connectionCheckHandler.post(connectionCheckRunnable!!)
    }

    private fun stopAutoConnectionCheck() {
        connectionCheckRunnable?.let {
            connectionCheckHandler.removeCallbacks(it)
        }
    }
}
