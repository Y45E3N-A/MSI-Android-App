package com.example.msiandroidapp

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.ui.gallery.ImagePagerAdapter
import java.io.File

class ImageViewerFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var emptyView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_image_viewer, container, false)
        viewPager = view.findViewById(R.id.imageViewPager)
        emptyView = view.findViewById(R.id.empty_view)

        // Retrieve file-path strings that were placed in the arguments bundle
        val imagePaths = arguments?.getStringArrayList("imagePaths") ?: arrayListOf()

        // Convert every string path to a File, then to a Uri
        val uris = imagePaths
            .map { File(it) }
            .filter { it.exists() && it.isFile && it.length() > 0L }
            .map { Uri.fromFile(it) }

        if (uris.isEmpty()) {
            emptyView.text = "No images found for this session"
            emptyView.visibility = View.VISIBLE
            viewPager.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
            viewPager.adapter = ImagePagerAdapter(uris)
            viewPager.offscreenPageLimit = 1
        }

        return view
    }

    companion object {
        fun newInstance(imagePaths: ArrayList<String>): ImageViewerFragment {
            return ImageViewerFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList("imagePaths", imagePaths)
                }
            }
        }
    }
}
