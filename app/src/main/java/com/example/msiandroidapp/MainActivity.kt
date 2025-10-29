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

    // track whether we've already started the upload service
    private var uploadServiceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val viewPager = binding.viewPager
        val bottomNav = binding.navView

        viewPager.adapter = ViewPagerAdapter(this)
        viewPager.offscreenPageLimit = 2

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_control -> viewPager.currentItem = 0
                R.id.navigation_gallery -> viewPager.currentItem = 1
            }
            true
        }

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

        checkLocationPermission()
        // DO NOT start the service here anymore
    }

    override fun onResume() {
        super.onResume()

        if (!uploadServiceStarted) {
            startUploadServiceSafely()
            uploadServiceStarted = true
        }
    }

    private fun startUploadServiceSafely() {
        val intent = Intent(this, UploadForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    fun goToStartPage() {
        binding.viewPager.currentItem = 0
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
            // you can handle denied/granted here if you want
        }
    }
}

