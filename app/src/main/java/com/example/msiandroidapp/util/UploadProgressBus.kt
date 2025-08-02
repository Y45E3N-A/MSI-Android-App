// UploadProgressBus.kt (anywhere in your codebase)
package com.example.msiandroidapp.util

import androidx.lifecycle.MutableLiveData

object UploadProgressBus {
    val uploadProgress = MutableLiveData<Pair<String, Int>>()
}
