package com.example.msiandroidapp.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.Session
import java.io.File
import java.util.Locale

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var emptyView: TextView

    private var sessionIdArg: Long = -1L
    private var imagePathsArg: ArrayList<String>? = null
    private val amsiWavelengths = intArrayOf(
        395, 415, 450, 470, 505, 528, 555, 570, 590, 610, 625, 640, 660, 730, 850, 880
    )
    private val calMetaCache = HashMap<String, CalMeta>()
    private data class PagerContext(
        val runId: String?,
        val sectionLabel: String?,
        val sectionIndex: Int?
    )

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            showEmpty("Permission required to display images.")
            return@registerForActivityResult
        }

        val paths = imagePathsArg
        if (paths != null && paths.isNotEmpty()) {
            loadFromPaths(paths)
        } else {
            loadSession(sessionIdArg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_session_detail)

        viewPager = findViewById(R.id.viewPager)
        emptyView = findViewById(R.id.empty_view)

        imagePathsArg = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS)
        sessionIdArg = intent.getLongExtra(EXTRA_SESSION_ID, -1L)

        val hasExplicitPaths = !imagePathsArg.isNullOrEmpty()
        if (!hasExplicitPaths && sessionIdArg <= 0L) {
            showEmpty("Missing session id.")
            return
        }

        // If your images are saved under a public folder (e.g. /storage/emulated/0/MSI_App/...),
        // Android 13+ needs READ_MEDIA_IMAGES at runtime. If you store under internal or
        // getExternalFilesDir(...), the permission is not required – this gate is safe either way.
        if (!hasReadImagesPermission()) {
            requestReadImagesPermission()
        } else {
            if (hasExplicitPaths) {
                loadFromPaths(imagePathsArg.orEmpty())
            } else {
                loadSession(sessionIdArg)
            }
        }
    }

    private fun hasReadImagesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-T: only needed if you read from public external storage.
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestReadImagesPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionRequester.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissionRequester.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadSession(sessionId: Long) {
        AppDatabase.getDatabase(applicationContext)
            .sessionDao()
            .getSessionById(sessionId)
            .observe(this) { s ->
                if (s == null) {
                    showEmpty("Session not found.")
                    return@observe
                }

                // 1) Use DB paths when present
                val urisFromDb: List<Uri> = buildSortedFiles(
                    s.imagePaths
                        .map(::File)
                        .filter { it.isFile && it.length() > 0 }
                ).map { Uri.fromFile(it) }

                // 2) Fallback: scan the parent folder of the first path
                val uris: List<Uri> = if (urisFromDb.isNotEmpty()) {
                    urisFromDb
                } else {
                    val parentDir = s.imagePaths.firstOrNull()?.let { File(it).parentFile }
                    val files = parentDir?.listFiles()?.toList().orEmpty()
                        .filter {
                            it.isFile && it.length() > 0 &&
                                    it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
                        }
                    buildSortedFiles(files).map { Uri.fromFile(it) }
                }

                if (uris.isEmpty()) {
                    showEmpty("No images found for this session.")
                    return@observe
                }

                emptyView.visibility = View.GONE
                viewPager.visibility = View.VISIBLE
                val ctx = PagerContext(
                    runId = s.runId,
                    sectionLabel = s.label,
                    sectionIndex = s.sectionIndex
                )
                viewPager.adapter = ImagePagerAdapter(buildImageItems(uris, session = s, pagerContext = ctx))
                viewPager.offscreenPageLimit = 1
            }
    }

    private fun loadFromPaths(imagePaths: List<String>) {
        val uris = imagePaths
            .map(::File)
            .filter { it.isFile && it.length() > 0 }
            .let { buildSortedFiles(it) }
            .map { Uri.fromFile(it) }

        if (uris.isEmpty()) {
            showEmpty("No images found for this calibration.")
            return
        }

        emptyView.visibility = View.GONE
        viewPager.visibility = View.VISIBLE
        viewPager.adapter = ImagePagerAdapter(buildImageItems(uris, session = null, pagerContext = null))
        viewPager.offscreenPageLimit = 1
    }

    private fun showEmpty(message: String) {
        viewPager.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyView.text = message
    }

    private fun buildImageItems(
        uris: List<Uri>,
        session: Session?,
        pagerContext: PagerContext?
    ): List<ImagePagerAdapter.ImageItem> {
        val totalFrames = uris.size
        return uris.mapIndexed { frameIdx, uri ->
            val file = uri.path?.let(::File)
            ImagePagerAdapter.ImageItem(
                uri = uri,
                title = file?.let {
                    buildTitle(
                        file = it,
                        frameIdx = frameIdx,
                        totalFrames = totalFrames,
                        session = session,
                        pagerContext = pagerContext
                    )
                } ?: "Image"
            )
        }
    }

    private fun buildSortedFiles(files: List<File>): List<File> {
        return files.sortedWith(
            compareBy<File> { file -> if (isDarkImage(file.name)) 0 else 1 }
                .thenBy { file -> extractImageIndex(file.name) ?: Int.MAX_VALUE }
                .thenBy { file -> file.name.lowercase(Locale.ROOT) }
        )
    }

    private fun buildTitle(
        file: File,
        frameIdx: Int,
        totalFrames: Int,
        session: Session?,
        pagerContext: PagerContext?
    ): String {
        val name = file.name
        val lower = name.lowercase(Locale.ROOT)
        val parts = ArrayList<String>(8)

        parts.add("File: $name")
        parts.add("Frame: ${frameIdx + 1}/$totalFrames")

        val normalizedPath = file.absolutePath.replace('\\', '/')
        val runId = pagerContext?.runId
            ?.takeIf { it.isNotBlank() }
            ?: extractCalRunId(normalizedPath)
            ?: extractPmfiRunId(normalizedPath)
        if (runId != null) {
            parts.add("RunId: $runId")
        }

        val pmfiSectionIndex = pagerContext?.sectionIndex
            ?: session?.takeIf { it.type.equals("PMFI", ignoreCase = true) }?.sectionIndex
            ?: extractPmfiSectionIndex(normalizedPath)
        val pmfiSectionLabel = pagerContext?.sectionLabel
            ?: session?.takeIf { it.type.equals("PMFI", ignoreCase = true) }?.label
            ?: extractPmfiSectionLabel(normalizedPath)
        if (pmfiSectionIndex != null || !pmfiSectionLabel.isNullOrBlank()) {
            val secIdxStr = pmfiSectionIndex?.toString() ?: "-"
            val secLabelStr = pmfiSectionLabel?.ifBlank { "-" } ?: "-"
            parts.add("Section: $secLabelStr  Index: $secIdxStr")
        }

        val idx = extractImageIndex(name)
        val wl = idx?.let { amsiWavelengths.getOrNull(it) }
        if (wl != null) {
            parts.add("Wavelength: $wl nm")
        }
        if (idx != null) {
            parts.add("Index: $idx")
        }

        val type = when {
            isDarkImage(lower) -> "Dark frame"
            lower.startsWith("cal_") -> "Calibration"
            lower.startsWith("image_") -> "AMSI"
            lower.startsWith("frame_") -> "PMFI"
            else -> "Image"
        }
        parts.add("Type: $type")

        val sizeKb = (file.length() / 1024.0)
        parts.add(String.format(Locale.US, "Size: %.1f KB", sizeKb))

        // Calibration metadata overlay (exp/gain/avg/norm)
        val calRunId = extractCalRunId(file.absolutePath)
        val chIdx = extractChannelIndex(name)
        val isDark = isDarkImage(name)
        if (calRunId != null && chIdx != null) {
            val meta = loadCalMetaIfNeeded(calRunId, file.parentFile ?: file)
            if (meta != null) {
                val expUs = meta.expUsByChannel[chIdx]
                val gain = meta.gainByChannel[chIdx]
                if (expUs != null || gain != null) {
                    val expStr = expUs?.let { "$it us" } ?: "—"
                    val gainStr = gain?.let { it.toString() } ?: "—"
                    parts.add("Exp: $expStr  Gain: $gainStr")
                }

                if (!isDark) {
                    val norm = meta.norms?.getOrNull(chIdx)
                    val avg = meta.avgDnByChannel[chIdx]
                    val avgPct = meta.avgPctByChannel[chIdx]
                    if (norm != null || avg != null || avgPct != null) {
                        val normStr = norm?.let { String.format(Locale.US, "%.4f", it) } ?: "—"
                        val avgStr = avg?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                        val pctStr = avgPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
                        parts.add("Norm: $normStr  Avg: $avgStr  ($pctStr)")
                    }
                }
            }
        }

        return parts.joinToString("\n")
    }

    private fun isDarkImage(name: String): Boolean {
        return name.lowercase(Locale.ROOT).contains("dark")
    }

    private fun extractImageIndex(name: String): Int? {
        val match = Regex("(?i)(?:cal_image_|image_|cal_channel_|frame_)(\\d+)").find(name)
            ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private data class CalMeta(
        val norms: List<Double>?,
        val avgDnByChannel: Map<Int, Double>,
        val avgPctByChannel: Map<Int, Double>,
        val expUsByChannel: Map<Int, Int>,
        val gainByChannel: Map<Int, Int>
    )

    private fun loadCalMetaIfNeeded(runId: String, dir: File): CalMeta? {
        calMetaCache[runId]?.let { return it }
        val metaFile = dir.listFiles()
            ?.firstOrNull { f ->
                val name = f.name.lowercase(Locale.ROOT)
                name.endsWith("_metadata.json") || (name.contains("metadata") && name.endsWith(".json"))
            } ?: return null

        return runCatching {
            val root = org.json.JSONObject(metaFile.readText())
            val normsArr = root.optJSONArray("led_norms")
            val norms = normsArr?.let { arr ->
                List(arr.length()) { i -> arr.optDouble(i) }
            }

            val avgDnByChannel = HashMap<Int, Double>()
            val avgPctByChannel = HashMap<Int, Double>()
            root.optJSONArray("results")?.let { results ->
                for (i in 0 until results.length()) {
                    val obj = results.optJSONObject(i) ?: continue
                    val ch = obj.optInt("channel", -1)
                    if (ch >= 0) {
                        val avg = obj.optDouble("avg_dn", Double.NaN)
                        if (!avg.isNaN()) avgDnByChannel[ch] = avg
                        val pct = obj.optDouble("avg_pct", Double.NaN)
                        if (!pct.isNaN()) avgPctByChannel[ch] = pct
                    }
                }
            }

            val expUsByChannel = HashMap<Int, Int>()
            val gainByChannel = HashMap<Int, Int>()
            root.optJSONArray("dark_images")?.let { darks ->
                for (i in 0 until darks.length()) {
                    val obj = darks.optJSONObject(i) ?: continue
                    val ch = obj.optInt("channel", -1)
                    if (ch >= 0) {
                        val expUs = obj.optInt("exp_us", -1)
                        val gain = obj.optInt("gain", -1)
                        if (expUs >= 0) expUsByChannel[ch] = expUs
                        if (gain >= 0) gainByChannel[ch] = gain
                    }
                }
            }
            root.optJSONArray("results")?.let { results ->
                for (i in 0 until results.length()) {
                    val obj = results.optJSONObject(i) ?: continue
                    val ch = obj.optInt("channel", -1)
                    if (ch >= 0) {
                        val expUs = obj.optInt("exp_us", -1)
                        val gain = obj.optInt("gain", -1)
                        if (expUs >= 0 && !expUsByChannel.containsKey(ch)) expUsByChannel[ch] = expUs
                        if (gain >= 0 && !gainByChannel.containsKey(ch)) gainByChannel[ch] = gain
                    }
                }
            }

            CalMeta(norms, avgDnByChannel, avgPctByChannel, expUsByChannel, gainByChannel)
                .also { calMetaCache[runId] = it }
        }.getOrNull()
    }

    private fun extractCalRunId(path: String): String? {
        val m = Regex("(?i)/CAL/([^/]+)/").find(path.replace('\\', '/')) ?: return null
        return m.groupValues.getOrNull(1)
    }

    private fun extractPmfiRunId(path: String): String? {
        val m = Regex("(?i)/PMFI/([^/]+?)(?:__[^/]+)?/").find(path) ?: return null
        return m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractPmfiSectionIndex(path: String): Int? {
        val m = Regex("(?i)/section_(\\d+)(?:__[^/]+)?/").find(path) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun extractPmfiSectionLabel(path: String): String? {
        val m = Regex("(?i)/section_\\d+__([^/]+)/").find(path) ?: return null
        return m.groupValues.getOrNull(1)
            ?.replace('_', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractChannelIndex(name: String): Int? {
        val dark = Regex("(?i)cal_dark_(\\d+)").find(name)
        if (dark != null) return dark.groupValues.getOrNull(1)?.toIntOrNull()
        val led = Regex("(?i)cal_image_(\\d+)").find(name)
        return led?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_IMAGE_PATHS = "image_paths"

        fun newIntent(context: Context, sessionId: Long): Intent =
            Intent(context, SessionDetailActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }

        fun newIntentForImagePaths(context: Context, imagePaths: ArrayList<String>): Intent =
            Intent(context, SessionDetailActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_IMAGE_PATHS, imagePaths)
            }
    }
}
