package com.example.msiandroidapp.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.msiandroidapp.ui.control.ControlFragment
import com.example.msiandroidapp.ui.gallery.GalleryFragment

class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 2
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ControlFragment()
            1 -> GalleryFragment()
            else -> ControlFragment()
        }
    }
}
