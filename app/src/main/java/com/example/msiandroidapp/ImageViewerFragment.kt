package com.example.msiandroidapp

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.ui.gallery.ImagePagerAdapter

class ImageViewerFragment : Fragment() {

    private lateinit var viewPager: ViewPager2

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_image_viewer, container, false)
        viewPager = view.findViewById(R.id.imageViewPager)

        val imagePaths = arguments?.getStringArrayList("imagePaths") ?: arrayListOf()
        val imageUris = imagePaths.map { Uri.parse(it) }  // Convert strings to Uris
        viewPager.adapter = ImagePagerAdapter(imageUris)

        return view
    }
}
