package com.example.msiandroidapp.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.AppDatabase
import java.io.File

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var emptyView: TextView

    private var sessionIdArg: Long = -1L

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadSession(sessionIdArg) else showEmpty("Permission required to display images.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_session_detail)

        viewPager = findViewById(R.id.viewPager)
        emptyView = findViewById(R.id.empty_view)

        sessionIdArg = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionIdArg <= 0L) {
            showEmpty("Missing session id.")
            return
        }

        // If your images are saved under a public folder (e.g. /storage/emulated/0/MSI_App/...),
        // Android 13+ needs READ_MEDIA_IMAGES at runtime. If you store under internal or
        // getExternalFilesDir(...), the permission is not required — this gate is safe either way.
        if (!hasReadImagesPermission()) {
            requestReadImagesPermission()
        } else {
            loadSession(sessionIdArg)
        }
    }

    private fun hasReadImagesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-T: only needed if you read from public external storage.
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestReadImagesPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionRequester.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissionRequester.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadSession(sessionId: Long) {
        AppDatabase.getDatabase(applicationContext)
            .sessionDao()
            .getSessionById(sessionId)
            .observe(this) { s ->
                if (s == null) {
                    showEmpty("Session not found.")
                    return@observe
                }

                // 1) Use DB paths when present
                val urisFromDb: List<Uri> = s.imagePaths
                    .map(::File)
                    .filter { it.isFile && it.length() > 0 }
                    .sortedBy { it.name.lowercase() }
                    .map { Uri.fromFile(it) }

                // 2) Fallback: scan the parent folder of the first path
                val uris: List<Uri> = if (urisFromDb.isNotEmpty()) {
                    urisFromDb
                } else {
                    val parentDir = s.imagePaths.firstOrNull()?.let { File(it).parentFile }
                    val files = parentDir?.listFiles()?.toList().orEmpty()
                        .filter {
                            it.isFile && it.length() > 0 &&
                                    it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
                        }
                        .sortedBy { it.name.lowercase() }
                    files.map { Uri.fromFile(it) }
                }

                if (uris.isEmpty()) {
                    showEmpty("No images found for this session.")
                    return@observe
                }

                emptyView.visibility = View.GONE
                viewPager.visibility = View.VISIBLE
                viewPager.adapter = ImagePagerAdapter(uris) // Adapter expects List<Uri>
                viewPager.offscreenPageLimit = 1
            }
    }

    private fun showEmpty(message: String) {
        viewPager.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyView.text = message
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"

        fun newIntent(context: Context, sessionId: Long): Intent =
            Intent(context, SessionDetailActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
    }
}
