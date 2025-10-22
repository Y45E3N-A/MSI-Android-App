package com.example.msiandroidapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.databinding.ActivityMainBinding
import com.example.msiandroidapp.service.UploadForegroundService
import com.example.msiandroidapp.ui.ViewPagerAdapter
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val viewPager = binding.viewPager
        val bottomNav = binding.navView

        // --- Setup ViewPager2 adapter ---
        viewPager.adapter = ViewPagerAdapter(this)
        viewPager.offscreenPageLimit = 2 // Keep both fragments alive

        // --- Sync: Tab click → Change ViewPager page ---
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_control -> viewPager.currentItem = 0
                R.id.navigation_gallery -> viewPager.currentItem = 1
            }
            true
        }


        // --- Sync: Swipe → Update selected tab ---
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                bottomNav.selectedItemId = when (position) {
                    0 -> R.id.navigation_control
                    1 -> R.id.navigation_gallery
                    else -> R.id.navigation_control
                }
            }
        })

        // --- Check location permission ---
        checkLocationPermission()

        // --- Start UploadForegroundService ---
        startUploadService()
    }
    fun goToStartPage() {
        val viewPager = binding.viewPager
        viewPager.currentItem = 0 // Control tab
    }
    private fun checkLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Location permission granted
            } else {
                // Location permission denied
            }
        }
    }

    private fun startUploadService() {
        val intent = Intent(this, UploadForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
