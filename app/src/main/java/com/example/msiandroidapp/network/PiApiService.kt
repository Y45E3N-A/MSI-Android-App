package com.example.msiandroidapp.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Query

interface PiApiService {

    @POST("/trigger")
    suspend fun triggerScript(@Query("button") buttonId: String): Response<Unit>

    // ---- NEW: Shutdown endpoint ----
    @POST("/shutdown")
    suspend fun shutdownSystem(): Response<Unit>

    companion object {
        private var retrofit: Retrofit? = null
        private var baseUrl = "http://192.168.4.1:5000"  // Default value (can be changed at runtime)

        val api: PiApiService
            get() = getRetrofit().create(PiApiService::class.java)

        fun setBaseUrl(ip: String) {
            baseUrl = "http://$ip:5000"
            retrofit = null  // Force rebuild with new IP
        }

        private fun getRetrofit(): Retrofit {
            if (retrofit == null) {
                retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            }
            return retrofit!!
        }
    }
}
