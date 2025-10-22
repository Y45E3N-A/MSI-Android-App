package com.example.msiandroidapp.ui.gallery

import android.app.Application
import androidx.lifecycle.*
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)

    // DAO already returns newest-first for sessions; we’ll re-sort after merge anyway.
    val sessions: LiveData<List<Session>> = db.sessionDao().getAllSessions()
    val calibrations: LiveData<List<CalibrationProfile>> = db.calibrationDao().getAll()

    // If you sometimes show an "In progress" row, expose it here; otherwise leave as MutableLiveData(emptyList()).
    private val inProgressItems = MutableLiveData<List<ResultListItem.InProgress>>(emptyList())

    // Merge + sort newest-first across all types
    val results: LiveData<List<ResultListItem>> =
        MediatorLiveData<List<ResultListItem>>().apply {
            var s: List<Session> = emptyList()
            var c: List<CalibrationProfile> = emptyList()
            var p: List<ResultListItem.InProgress> = emptyList()

            fun Long?.orZero() = this ?: 0L

            fun publish() {
                val merged = buildList {
                    addAll(p) // in-progress first (optional; see sort below)
                    addAll(s.map { ResultListItem.SessionItem(it) })
                    addAll(c.map { ResultListItem.CalibrationItem(it) })
                }

                // Sort rule:
                //  - InProgress: push to top by giving a huge sort key (Long.MAX_VALUE)
                //  - Session: use createdAt (or completedAt if you add it later)
                //  - Calibration: use createdAt (rename if your field differs)
                value = merged.sortedByDescending { item ->
                    when (item) {
                        is ResultListItem.InProgress -> Long.MAX_VALUE
                        is ResultListItem.SessionItem -> item.session.createdAt.orZero()
                        is ResultListItem.CalibrationItem -> item.profile.createdAt.orZero() // <-- profile, not calibration
                    }
                }
            }

            addSource(sessions) { s = it ?: emptyList(); publish() }
            addSource(calibrations) { c = it ?: emptyList(); publish() }
            addSource(inProgressItems) { p = it ?: emptyList(); publish() }
        }.distinctUntilChanged()

    // Call this from your UI when a PMFI/AMSI run is in progress to show a row at the top.
    fun setInProgress(list: List<ResultListItem.InProgress>) {
        inProgressItems.value = list
    }
}
