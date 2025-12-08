package com.example.msiandroidapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
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
        private const val MEDIA_PERMISSION_REQUEST_CODE = 1002
    }

    private var uploadServiceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val viewPager: ViewPager2 = binding.viewPager
        val bottomNav: BottomNavigationView = binding.navView

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
                bottomNav.selectedItemId = when (position) {
                    0 -> R.id.navigation_control
                    1 -> R.id.navigation_gallery
                    else -> R.id.navigation_control
                }
            }
        })

        checkLocationPermission()
        checkMediaPermission()
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

    // ------------------------------------------------------------------
    // LOCATION PERMISSIONS
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // MEDIA PERMISSIONS (PHOTOS + VIDEOS)
    // ------------------------------------------------------------------
    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val img = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            val vid = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            img && vid
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkMediaPermission() {
        if (hasMediaPermission()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                ),
                MEDIA_PERMISSION_REQUEST_CODE
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                MEDIA_PERMISSION_REQUEST_CODE
            )
        }
    }

    // ------------------------------------------------------------------
    // PERMISSION CALLBACK HANDLING
    // ------------------------------------------------------------------
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                // optional: handle denied location
            }

            MEDIA_PERMISSION_REQUEST_CODE -> {
                if (!hasMediaPermission()) {
                    showMediaPermissionDialog()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // DIALOG TO EXPLAIN MEDIA PERMISSION
    // ------------------------------------------------------------------
    private fun showMediaPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Photos & Videos Permission Needed")
            .setMessage(
                "This app needs access to Photos and Videos in order to save and display " +
                        "captured multispectral images in the Results. Please grant permission - Allow All."
            )
            .setCancelable(false)
            .setPositiveButton("Retry") { _, _ ->
                checkMediaPermission()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
