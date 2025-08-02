package com.example.msiandroidapp.ui.gallery

import android.graphics.Bitmap
import com.example.msiandroidapp.data.Session

sealed class SessionListItem {
    data class InProgress(val bitmaps: List<Bitmap?>, val imageCount: Int) : SessionListItem()
    data class Completed(val session: Session) : SessionListItem()
}
