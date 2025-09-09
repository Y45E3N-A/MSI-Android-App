package com.example.msiandroidapp.ui.control

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ControlViewModel : ViewModel() {

    // ----- Existing capture state -----
    val capturedBitmaps = MutableLiveData(MutableList<Bitmap?>(16) { null })
    val imageCount = MutableLiveData(0)
    val isCapturing = MutableLiveData(false)
    val sessionComplete = MutableLiveData(false)

    fun addBitmap(idx: Int, bitmap: Bitmap) {
        val current = (capturedBitmaps.value ?: MutableList<Bitmap?>(16) { null }).toMutableList()
        if (idx in 0..15) current[idx] = bitmap
        capturedBitmaps.postValue(current)

        val count = current.count { it != null }
        imageCount.postValue(count)
        if (count == 16) sessionComplete.postValue(true)
    }

    fun resetCapture() {
        capturedBitmaps.postValue(MutableList(16) { null })
        imageCount.postValue(0)
        isCapturing.postValue(false)
        sessionComplete.postValue(false)
    }

    // ----- Calibration state (NEW) -----
    // Whether a calibration run is in progress
    val isCalibrating = MutableLiveData(false)

    // Per-step progress fields (mirror server payload so UI can bind directly)
    val calChannelIndex = MutableLiveData(0)         // 0-based index of current channel
    val calTotalChannels = MutableLiveData(16)       // usually 16
    val calWavelengthNm = MutableLiveData<Int?>(null)
    val calAverageIntensity = MutableLiveData<Double?>(null)
    val calNormPrev = MutableLiveData<Double?>(null)
    val calNormNew = MutableLiveData<Double?>(null)

    // Final result from /cal_complete for persistence/next run
    val ledNorms = MutableLiveData<List<Double>?>(null)

    fun startCalibration(totalChannels: Int = 16) {
        isCalibrating.postValue(true)
        calChannelIndex.postValue(0)
        calTotalChannels.postValue(totalChannels)
        calWavelengthNm.postValue(null)
        calAverageIntensity.postValue(null)
        calNormPrev.postValue(null)
        calNormNew.postValue(null)
    }

    fun updateCalibrationProgress(
        channelIndex: Int,
        totalChannels: Int,
        wavelengthNm: Int?,
        averageIntensity: Double?,
        normPrev: Double?,
        normNew: Double?
    ) {
        calChannelIndex.postValue(channelIndex)
        calTotalChannels.postValue(totalChannels)
        calWavelengthNm.postValue(wavelengthNm)
        calAverageIntensity.postValue(averageIntensity)
        calNormPrev.postValue(normPrev)
        calNormNew.postValue(normNew)
    }

    fun completeCalibration(updatedNorms: List<Double>?) {
        if (updatedNorms != null && updatedNorms.size == 16) {
            ledNorms.postValue(updatedNorms)
        }
        isCalibrating.postValue(false)
    }

    fun failCalibration() {
        isCalibrating.postValue(false)
    }
}
