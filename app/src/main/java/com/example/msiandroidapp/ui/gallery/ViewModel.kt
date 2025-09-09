package com.example.msiandroidapp.ui.gallery

import android.app.Application
import androidx.lifecycle.*
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val sessions: LiveData<List<Session>> = db.sessionDao().getAllSessions()
    val calibrations: LiveData<List<CalibrationProfile>> = db.calibrationDao().getAll()

    // Merge into a single Results feed, newest first
    val results: LiveData<List<ResultListItem>> = MediatorLiveData<List<ResultListItem>>().apply {
        var s: List<Session> = emptyList()
        var c: List<CalibrationProfile> = emptyList()
        fun publish() {
            val items = buildList {
                addAll(s.map { ResultListItem.SessionItem(it) })
                addAll(c.map { ResultListItem.CalibrationItem(it) })
            }.sortedByDescending {
                when (it) {
                    is ResultListItem.SessionItem -> it.session.id
                    is ResultListItem.CalibrationItem -> it.profile.id
                    else -> Long.MIN_VALUE
                }
            }
            value = items
        }
        addSource(db.sessionDao().getAllSessions()) { s = it; publish() }
        addSource(db.calibrationDao().getAll()) { c = it; publish() }
    }

}
