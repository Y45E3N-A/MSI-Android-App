package com.example.msiandroidapp.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.Session

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    val allSessions: LiveData<List<Session>> =
        AppDatabase.getDatabase(application).sessionDao().getAllSessions()
}
