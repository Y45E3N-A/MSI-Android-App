package com.example.msiandroidapp.ui.control

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ControlViewModel : ViewModel() {
    val capturedBitmaps = MutableLiveData<MutableList<Bitmap?>>(
        MutableList<Bitmap?>(16) { null }
    )
    val imageCount = MutableLiveData<Int>(0)
    val isCapturing = MutableLiveData<Boolean>(false)

    val sessionComplete = MutableLiveData<Boolean>(false)

    fun addBitmap(idx: Int, bitmap: Bitmap) {
        val current = capturedBitmaps.value ?: MutableList<Bitmap?>(16) { null }
        current[idx] = bitmap
        capturedBitmaps.postValue(current)
        val count = current.count { it != null }
        imageCount.postValue(count)
        if (count == 16) {
            sessionComplete.postValue(true)
        }
    }


}
