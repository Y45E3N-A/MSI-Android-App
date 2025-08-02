package com.example.msiandroidapp.ui.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.AppDatabase
import android.util.Log
import java.io.File

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)

        viewPager = findViewById(R.id.viewPager)
        val sessionId = intent.getLongExtra("session_id", -1L)
        if (sessionId == -1L) return  // or show error UI

        AppDatabase.getDatabase(applicationContext)
            .sessionDao()
            .getSessionById(sessionId)
            .observe(this) { session ->
                if (session != null) {
                    Log.d("SessionDetail", "Loaded session with images: ${session.imagePaths}")
                    val imageUris = session.imagePaths.map { Uri.fromFile(File(it)) }  // use Uri.fromFile!
                    viewPager.adapter = ImagePagerAdapter(imageUris)
                } else {
                    Log.e("SessionDetail", "No session found!")
                }
            }
    }

    companion object {
        fun newIntent(context: Context, sessionId: Long): Intent {
            val intent = Intent(context, SessionDetailActivity::class.java)
            intent.putExtra("session_id", sessionId)
            return intent
        }
    }
}
