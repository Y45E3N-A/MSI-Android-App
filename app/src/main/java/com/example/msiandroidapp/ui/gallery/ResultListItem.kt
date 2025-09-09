package com.example.msiandroidapp.ui.gallery

import android.graphics.Bitmap
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session

sealed class ResultListItem {
    data class InProgress(val bitmaps: List<Bitmap?>, val imageCount: Int) : ResultListItem()
    data class SessionItem(val session: Session) : ResultListItem()
    data class CalibrationItem(val profile: CalibrationProfile) : ResultListItem()
}
