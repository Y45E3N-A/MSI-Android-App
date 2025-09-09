package com.example.msiandroidapp.ui.gallery

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.ui.control.ControlViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

class GalleryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultsAdapter
    private val galleryViewModel: GalleryViewModel by viewModels()
    private val controlViewModel: ControlViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_gallery, container, false)
        recyclerView = root.findViewById(R.id.gallery_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = ResultsAdapter(
            onSessionClick = this::openSession,
            onSessionLongClick = this::onSessionLongClicked,
            onCalibrationClick = this::openCalibration,
            onCalibrationLongClick = this::onCalibrationLongClicked
        )
        recyclerView.adapter = adapter
        return root
    }
    private fun deleteSession(session: Session) {
        // Optionally remove the image files too
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Delete files from storage (ignore failures)
                session.imagePaths.forEach { path ->
                    runCatching { File(path).delete() }
                }
                // Delete DB row
                AppDatabase.getDatabase(requireContext().applicationContext)
                    .sessionDao()
                    .delete(session)
            } catch (e: Exception) {
                // Swallow or log if you prefer
                e.printStackTrace()
            }
            // LiveData from Room will auto-update the list
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Combine results + in-progress preview
        galleryViewModel.results.observe(viewLifecycleOwner) { results ->
            submitMerged(results)
        }
        // Track capture state for the in-progress banner/grid
        listOf(
            controlViewModel.isCapturing,
            controlViewModel.capturedBitmaps,
            controlViewModel.imageCount
        ).forEach { live ->
            live.observe(viewLifecycleOwner) { submitMerged(galleryViewModel.results.value ?: emptyList()) }
        }
    }

    private fun buildInProgressItemOrNull(): ResultListItem.InProgress? {
        val isCapturing = controlViewModel.isCapturing.value ?: false
        val bitmaps = controlViewModel.capturedBitmaps.value ?: emptyList()
        val imageCount = controlViewModel.imageCount.value ?: 0
        return if ((isCapturing || imageCount < 16) && bitmaps.any { it != null }) {
            ResultListItem.InProgress(bitmaps, imageCount)
        } else if (imageCount == 16 && bitmaps.any { it != null }) {
            // show "All images received" grid for 2s then drop
            handler.postDelayed({ submitMerged(galleryViewModel.results.value ?: emptyList(), forceHideInProgress = true) }, 2000)
            ResultListItem.InProgress(bitmaps, imageCount)
        } else null
    }

    private var hideInProgressOnce = false
    private fun submitMerged(results: List<ResultListItem>, forceHideInProgress: Boolean = false) {
        if (forceHideInProgress) hideInProgressOnce = true
        val merged = mutableListOf<ResultListItem>()
        val inProg = if (!hideInProgressOnce) buildInProgressItemOrNull() else null
        if (inProg != null) merged.add(inProg)
        merged.addAll(results)
        adapter.submitList(merged)
    }

    private fun openSession(session: Session) {
        startActivity(SessionDetailActivity.newIntent(requireContext(), session.id))
    }

    private fun onSessionLongClicked(session: Session) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Session Options")
            .setItems(arrayOf("Share", "Delete")) { _, which ->
                when (which) {
                    0 -> shareSession(session)
                    1 -> deleteSession(session)
                }
            }
            .show()
    }

    private fun shareSession(session: Session) {
        val uris = session.imagePaths.mapNotNull { path ->
            val file = File(path)
            if (file.exists()) {
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
            } else null
        }

        if (uris.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage("No images found to share.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Session"))
    }


    private fun openCalibration(profile: CalibrationProfile) {
        // Order must match the Pi’s LED index order (0..15)
        val wavelengthsNm = intArrayOf(
            570, 555, 528, 415, 395, 450, 470, 505,
            640, 660, 730, 850, 880, 625, 610, 590
        )

        val lines = if (profile.ledNorms.size == 16) {
            profile.ledNorms.mapIndexed { i, n ->
                val wl = wavelengthsNm.getOrNull(i)?.toString() ?: "Ch$i"
                "• ${wl} nm  —  ${"%.3f".format(n)}"
            }
        } else {
            listOf("No per-channel norms stored.")
        }

        val fullMsg = buildString {
            appendLine(profile.summary ?: "Calibration")
            appendLine()
            appendLine("Per-channel norms:")
            lines.forEach { appendLine(it) }
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(profile.name)
            .setMessage(fullMsg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun onCalibrationLongClicked(profile: CalibrationProfile) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Calibration Options")
            .setItems(arrayOf("Delete")) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext()).calibrationDao().delete(profile)
                }
            }.show()
    }
}
