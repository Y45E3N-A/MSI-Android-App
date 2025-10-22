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
    val pmfiTotalFrames = MutableLiveData(0)
    val pmfiDoneFrames  = MutableLiveData(0)
    val pmfiCurrentSection = MutableLiveData<String?>(null)
    val pmfiFps = MutableLiveData<Double?>(null)
    val pmfiEta = MutableLiveData<Int?>(null)
    val pmfiPercent = MutableLiveData(0)                // 0..100
    val pmfiSectionFrames = MutableLiveData(0)          // frames in current section
    val pmfiSectionState = MutableLiveData<String?>()   // "capturing" | "packing" | "uploaded ..." | etc.
    val pmfiComplete = MutableLiveData(false)
    val pmfiLogLine = MutableLiveData<String?>()
    val pmfiSectionIndex   = MutableLiveData(0)       // 0-based, for UI show +1
    val pmfiSectionCount   = MutableLiveData(0)       // total sections in plan
    val pmfiSectionDone    = MutableLiveData(0)       // frames done in THIS section
    val pmfiSectionTotal   = MutableLiveData(0)       // frames in THIS section
    val pmfiSectionPercent = MutableLiveData(0)       // 0..100 for THIS section
    val pmfiSectionInfo    = MutableLiveData<String?>() // human-readable “meaning” line
    fun addBitmap(idx: Int, bitmap: Bitmap) {
        val current = (capturedBitmaps.value ?: MutableList<Bitmap?>(16) { null }).toMutableList()
        if (idx in 0..15) current[idx] = bitmap
        capturedBitmaps.postValue(current)

        val count = current.count { it != null }
        imageCount.postValue(count)
        if (count == 16) sessionComplete.postValue(true)
    }
    fun resetToIdle() {
        // --- AMSI capture ---
        capturedBitmaps.postValue(MutableList(16) { null })
        imageCount.postValue(0)
        isCapturing.postValue(false)
        sessionComplete.postValue(false)

        // --- PMFI (global + per-section) ---
        pmfiTotalFrames.postValue(0)
        pmfiDoneFrames.postValue(0)
        pmfiPercent.postValue(0)
        pmfiCurrentSection.postValue(null)
        pmfiFps.postValue(null)
        pmfiEta.postValue(null)
        pmfiSectionFrames.postValue(0)
        pmfiSectionState.postValue(null)
        pmfiComplete.postValue(false)
        pmfiLogLine.postValue(null)
        pmfiSectionIndex.postValue(0)
        pmfiSectionCount.postValue(0)
        pmfiSectionDone.postValue(0)
        pmfiSectionTotal.postValue(0)
        pmfiSectionPercent.postValue(0)
        pmfiSectionInfo.postValue(null)

        // --- Calibration ---
        isCalibrating.postValue(false)
        calChannelIndex.postValue(0)
        calTotalChannels.postValue(16)
        calWavelengthNm.postValue(null)
        calAverageIntensity.postValue(null)
        calNormPrev.postValue(null)
        calNormNew.postValue(null)
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
