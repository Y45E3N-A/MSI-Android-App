package com.example.msiandroidapp

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.ui.gallery.ImagePagerAdapter
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class ImageViewerFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var emptyView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_image_viewer, container, false)
        viewPager = view.findViewById(R.id.imageViewPager)
        emptyView = view.findViewById(R.id.empty_view)

        // Retrieve file-path strings that were placed in the arguments bundle
        val imagePaths = arguments?.getStringArrayList("imagePaths") ?: arrayListOf()

        // Convert every string path to a File, then to a Uri
        val files = imagePaths
            .map { File(it) }
            .filter { it.exists() && it.isFile && it.length() > 0L }
        val uris = files.map { Uri.fromFile(it) }

        if (uris.isEmpty()) {
            emptyView.text = "No images found for this session"
            emptyView.visibility = View.VISIBLE
            viewPager.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
            viewPager.adapter = ImagePagerAdapter(buildImageItems(files, uris))
            viewPager.offscreenPageLimit = 1
        }

        return view
    }

    private data class CalMeta(
        val norms: List<Double>?,
        val avgDnByChannel: Map<Int, Double>,
        val avgPctByChannel: Map<Int, Double>,
        val expUsByChannel: Map<Int, Int>,
        val gainByChannel: Map<Int, Int>
    )

    private fun buildImageItems(
        files: List<File>,
        uris: List<Uri>
    ): List<ImagePagerAdapter.ImageItem> {
        val calMetaCache = HashMap<String, CalMeta>()

        fun loadCalMetaIfNeeded(runId: String, dir: File): CalMeta? {
            calMetaCache[runId]?.let { return it }
            val metaFile = dir.listFiles()
                ?.firstOrNull { f ->
                    val name = f.name.lowercase(Locale.ROOT)
                    name.endsWith("_metadata.json") || (name.contains("metadata") && name.endsWith(".json"))
                } ?: return null

            return runCatching {
                val root = JSONObject(metaFile.readText())
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

        fun extractCalRunId(path: String): String? {
            val m = Regex("(?i)/CAL/([^/]+)/").find(path.replace('\\', '/')) ?: return null
            return m.groupValues.getOrNull(1)
        }

        fun extractChannelIndex(name: String): Int? {
            val dark = Regex("(?i)cal_dark_(\\d+)").find(name)
            if (dark != null) return dark.groupValues.getOrNull(1)?.toIntOrNull()
            val led = Regex("(?i)cal_image_(\\d+)").find(name)
            return led?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return uris.mapIndexed { idx, uri ->
            val file = files.getOrNull(idx)
            val fileName = file?.name ?: "Image"
            val titleLines = mutableListOf<String>()
            titleLines += "File: $fileName"

            if (file != null) {
                val runId = extractCalRunId(file.absolutePath)
                if (runId != null) {
                    val meta = loadCalMetaIfNeeded(runId, file.parentFile ?: file)
                    val chIdx = extractChannelIndex(fileName)
                    if (meta != null && chIdx != null) {
                        val expUs = meta.expUsByChannel[chIdx]
                        val gain = meta.gainByChannel[chIdx]
                        if (expUs != null || gain != null) {
                            val expStr = expUs?.let { "$it us" } ?: "—"
                            val gainStr = gain?.let { it.toString() } ?: "—"
                            titleLines += "Exp: $expStr  Gain: $gainStr"
                        }

                        val norm = meta.norms?.getOrNull(chIdx)
                        val avg = meta.avgDnByChannel[chIdx]
                        val avgPct = meta.avgPctByChannel[chIdx]
                        if (norm != null || avg != null || avgPct != null) {
                            val normStr = norm?.let { String.format(Locale.US, "%.4f", it) } ?: "—"
                            val avgStr = avg?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                            val pctStr = avgPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
                            titleLines += "Norm: $normStr  Avg: $avgStr  (${pctStr})"
                        }
                    }
                }
            }

            ImagePagerAdapter.ImageItem(
                uri = uri,
                title = titleLines.joinToString("\n")
            )
        }
    }

    companion object {
        fun newInstance(imagePaths: ArrayList<String>): ImageViewerFragment {
            return ImageViewerFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList("imagePaths", imagePaths)
                }
            }
        }
    }
}
