package com.example.msiandroidapp.ui.gallery

import android.app.Application
import androidx.lifecycle.*
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)

    // Sessions + Calibrations from Room
    val sessions: LiveData<List<Session>> = db.sessionDao().getAllSessions()
    val calibrations: LiveData<List<CalibrationProfile>> = db.calibrationDao().getAll()

    // "In progress" row(s) from live capture (AMSI/PMFI still streaming)
    private val inProgressItems = MutableLiveData<List<ResultListItem.InProgress>>(emptyList())

    // Public merged list for UI
    val results: LiveData<List<ResultListItem>> =
        MediatorLiveData<List<ResultListItem>>().apply {
            var s: List<Session> = emptyList()
            var c: List<CalibrationProfile> = emptyList()
            var p: List<ResultListItem.InProgress> = emptyList()

            fun Long?.orZero() = this ?: 0L

            fun publish() {
                val merged = buildList {
                    addAll(p) // in-progress rows (if any)
                    addAll(s.map { ResultListItem.SessionItem(it) })
                    addAll(c.map { ResultListItem.CalibrationItem(it) })
                }

                value = merged.sortedByDescending { item ->
                    when (item) {
                        is ResultListItem.InProgress -> Long.MAX_VALUE
                        is ResultListItem.SessionItem -> {
                            val sess = item.session
                            when {
                                sess.completedAtMillis != null && sess.completedAtMillis > 0L ->
                                    sess.completedAtMillis
                                sess.createdAt > 0L ->
                                    sess.createdAt
                                else ->
                                    0L
                            }
                        }
                        is ResultListItem.CalibrationItem -> item.profile.completedAtMillis
                    }
                }
            }


            addSource(sessions)      { s = it ?: emptyList(); publish() }
            addSource(calibrations)  { c = it ?: emptyList(); publish() }
            addSource(inProgressItems) { p = it ?: emptyList(); publish() }
        }.distinctUntilChanged()

    // Call from UI to reflect active capture
    fun setInProgress(list: List<ResultListItem.InProgress>) {
        inProgressItems.value = list
    }
}
