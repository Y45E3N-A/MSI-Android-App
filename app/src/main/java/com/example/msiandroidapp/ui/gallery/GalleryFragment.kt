package com.example.msiandroidapp.ui.gallery

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.ui.control.ControlViewModel
import com.example.msiandroidapp.network.PiSocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import androidx.lifecycle.lifecycleScope

class GalleryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SessionAdapter
    private val galleryViewModel: GalleryViewModel by viewModels()
    private val controlViewModel: ControlViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_gallery, container, false)
        recyclerView = root.findViewById(R.id.gallery_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SessionAdapter(
            onClick = this::onSessionClicked,
            onLongClick = this::onSessionLongClicked
        )
        recyclerView.adapter = adapter
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure PiSocketManager is connected (it should be, from ControlFragment or initial app startup)
        // Do NOT call disconnect here. You can listen to events if you want, but connection remains alive.

        // Observe sessions from the ViewModel and also the shared capture state
        galleryViewModel.allSessions.observe(viewLifecycleOwner) { sessions ->
            updateAdapterList(sessions)
        }
        // Observe the shared capture state
        controlViewModel.isCapturing.observe(viewLifecycleOwner) { _ ->
            updateAdapterList(galleryViewModel.allSessions.value ?: emptyList())
        }
        controlViewModel.capturedBitmaps.observe(viewLifecycleOwner) { _ ->
            updateAdapterList(galleryViewModel.allSessions.value ?: emptyList())
        }
        controlViewModel.imageCount.observe(viewLifecycleOwner) { _ ->
            updateAdapterList(galleryViewModel.allSessions.value ?: emptyList())
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun updateAdapterList(dbSessions: List<Session>) {
        val isCapturing = controlViewModel.isCapturing.value ?: false
        val bitmaps = controlViewModel.capturedBitmaps.value ?: emptyList()
        val imageCount = controlViewModel.imageCount.value ?: 0

        val result = mutableListOf<SessionListItem>()

        // Show in-progress while capturing or not yet full
        if ((isCapturing || imageCount < 16) && bitmaps.any { it != null }) {
            result.add(SessionListItem.InProgress(bitmaps, imageCount))
        }
        // Show "All images received" for 2 seconds
        else if (imageCount == 16) {
            result.add(SessionListItem.InProgress(bitmaps, imageCount))
            handler.postDelayed({
                refreshCompletedOnly(dbSessions)
            }, 2000)
        }

        // Add completed sessions
        result.addAll(dbSessions.map { SessionListItem.Completed(it) })
        adapter.submitList(result)
    }

    private fun refreshCompletedOnly(dbSessions: List<Session>) {
        adapter.submitList(dbSessions.map { SessionListItem.Completed(it) })
    }




    private fun onSessionClicked(session: Session) {
        // If clicked on a completed session, open the detail activity
        val intent = SessionDetailActivity.newIntent(requireContext(), session.id)
        startActivity(intent)
    }

    private fun onSessionLongClicked(session: Session) {
        // Show a dialog for share or delete
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
        val uris = session.imagePaths.map { path ->
            val file = File(path)
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Session"))
    }

    private fun deleteSession(session: Session) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(requireContext()).sessionDao().delete(session)
        }
    }


}
