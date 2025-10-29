package com.example.msiandroidapp.network

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.util.concurrent.TimeUnit

// ------------------------------
// DTOs (align with server responses)
// ------------------------------

// /pmfi/start response: {"ok":true,"session_id":"...","config_id":"..."} or {"status":"busy"} or {"error":"..."}
data class PmfiStartResponse(
    val ok: Boolean? = null,
    val session_id: String? = null,
    val config_id: String? = null,
    val status: String? = null,
    val error: String? = null,
    val plan: Map<String, Any?>? = null
)


// Optional: /pmfi/status (shape may vary; keep fields nullable)
data class PmfiStatusResponse(
    val running: Boolean? = null,
    val current_section_index: Int? = null,
    val sections: List<Map<String, Any?>>? = null,
    val session_id: String? = null,
    val config_id: String? = null
)

// /ini/list response: {"files":[{"name":"x.ini","size":1234}, ...]}
data class IniListResponse(
    val files: List<IniFile> = emptyList()
)
data class IniFile(
    val name: String,
    val size: Long
)

// /ini/upload response: {"status":"saved","name":"file.ini"} or {"error":"..."}
data class IniUploadResponse(
    val status: String? = null,
    val name: String? = null,
    val error: String? = null
)

// /ini/get response: {"name":"file.ini","ini_text":"[pmfi] ..."} or {"error":"..."}
data class IniGetResponse(
    val name: String? = null,
    val ini_text: String? = null,
    val error: String? = null
)

// Body for /pmfi/start (server accepts any of these; at least one config must be provided)
data class PmfiStartBody(
    val ini_text: String,
    val session_id: String,
    val upload_mode: String = "zip"
)

// ------------------------------
// Retrofit service
// ------------------------------
interface PiApiService {

    // --- Core ---
    @GET("/status")
    suspend fun status(): Response<ResponseBody> // plain "OK"

    @POST("/shutdown")
    suspend fun shutdownSystem(): Response<ResponseBody>

    @POST("/trigger")
    suspend fun triggerButton(@Query("button") buttonId: String): Response<ResponseBody> // SW2/SW3/SW4
    @POST("/abort")
    suspend fun abortAll(
        @Query("reason") reason: String = "aborted by client"
    ): retrofit2.Response<Unit>

    // --- PMFI ---
    @POST("/pmfi/start")
    suspend fun pmfiStart(@Body body: PmfiStartBody): Response<PmfiStartResponse>

    @POST("/pmfi/stop")
    suspend fun pmfiStop(): Response<ResponseBody>

    // Optional: light polling (most PMFI updates come via Socket.IO)
    @GET("/pmfi/status")
    suspend fun pmfiStatus(): Response<PmfiStatusResponse>

    // --- INI management (for PMFI configs) ---
    @Multipart
    @POST("/ini/upload")
    suspend fun iniUpload(@Part file: MultipartBody.Part): Response<IniUploadResponse>

    @GET("/ini/list")
    suspend fun iniList(): Response<IniListResponse>

    @GET("/ini/get")
    suspend fun iniGet(@Query("name") name: String): Response<IniGetResponse>

    companion object {
        // Singleton setup with sane timeouts for Pi on hotspot
        @Volatile private var retrofit: Retrofit? = null
        @Volatile private var baseUrl: String = "http://192.168.4.1:5000"

        private fun okHttp(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        private fun buildRetrofit(): Retrofit =
            Retrofit.Builder()
                .baseUrl(baseUrl.ensureHttpBaseUrl())
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttp())
                .build()

        fun setBaseUrl(ipOrUrl: String) {
            baseUrl = ipOrUrl.ensureHttpBaseUrl()
            retrofit = null // force rebuild on next access
        }

        val api: PiApiService
            get() {
                val r = retrofit ?: synchronized(this) {
                    retrofit ?: buildRetrofit().also { retrofit = it }
                }
                return r.create(PiApiService::class.java)
            }
    }
}

// ------------------------------
// Public facade + helpers
// ------------------------------
object PiApi {
    fun setBaseUrl(ipOrUrl: String) = PiApiService.setBaseUrl(ipOrUrl)
    val api: PiApiService get() = PiApiService.api

    /** Build a multipart part for /ini/upload */
    fun makeIniPart(file: File): MultipartBody.Part {
        val body: RequestBody =
            file.asRequestBody("text/plain".toMediaTypeOrNull()) // server just needs bytes; text/plain is fine
        return MultipartBody.Part.createFormData("file", file.name, body)
    }
}

// ------------------------------
// Extensions
// ------------------------------
private fun String.ensureHttpBaseUrl(): String {
    val s = this.trim()
    val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
    // If user passed bare IP without :port, append :5000
    return if (Regex(""".*:\d+$""").matches(withScheme)) withScheme else "$withScheme:5000"
}
