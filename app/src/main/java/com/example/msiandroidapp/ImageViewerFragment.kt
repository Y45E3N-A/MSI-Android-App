package com.example.msiandroidapp

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.msiandroidapp.ui.gallery.ImagePagerAdapter
import com.example.msiandroidapp.ui.gallery.ImageSelectorAdapter
import com.example.msiandroidapp.util.PngHeaderReader
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class ImageViewerFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var emptyView: TextView
    private lateinit var selectorRecycler: RecyclerView
    private var selectorAdapter: ImageSelectorAdapter? = null
    private val amsiWavelengths = intArrayOf(
        395, 415, 450, 470, 505, 528, 555, 570, 590, 610, 625, 640, 660, 730, 850, 880
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_image_viewer, container, false)
        viewPager = view.findViewById(R.id.imageViewPager)
        emptyView = view.findViewById(R.id.empty_view)
        selectorRecycler = view.findViewById(R.id.image_selector_recycler)
        selectorRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

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
            selectorRecycler.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
            setupImagePager(buildImageItems(files, uris))
        }

        return view
    }

    private fun setupImagePager(items: List<ImagePagerAdapter.ImageItem>) {
        selectorAdapter = ImageSelectorAdapter(items) { position ->
            viewPager.setCurrentItem(position, true)
        }
        selectorRecycler.adapter = selectorAdapter
        selectorRecycler.visibility = View.VISIBLE

        viewPager.adapter = ImagePagerAdapter(items) { position, isZoomed ->
            if (position == viewPager.currentItem) {
                selectorRecycler.visibility = if (isZoomed) View.GONE else View.VISIBLE
            }
        }
        viewPager.offscreenPageLimit = 1
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectorAdapter?.setSelected(position)
                selectorRecycler.smoothScrollToPosition(position)
            }
        })
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
            titleLines += "Frame: ${idx + 1}/${uris.size}"

            if (file != null) {
                val normalizedPath = file.absolutePath.replace('\\', '/')
                val runId = extractCalRunId(normalizedPath) ?: extractPmfiRunId(normalizedPath)
                if (runId != null) {
                    titleLines += "RunId: $runId"
                }
                PngHeaderReader.read(file)?.let { titleLines += it.shortLabel }

                val pmfiSectionIndex = extractPmfiSectionIndex(normalizedPath)
                val pmfiSectionLabel = extractPmfiSectionLabel(normalizedPath)
                if (pmfiSectionIndex != null || !pmfiSectionLabel.isNullOrBlank()) {
                    val secIdxStr = pmfiSectionIndex?.toString() ?: "-"
                    val secLabelStr = pmfiSectionLabel?.ifBlank { "-" } ?: "-"
                    titleLines += "Section: $secLabelStr  Index: $secIdxStr"
                }

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
                title = titleLines.joinToString("\n"),
                selectorLabel = file?.let { buildSelectorLabel(it, idx) } ?: "Frame ${idx + 1}"
            )
        }
    }

    private fun buildSelectorLabel(file: File, frameIdx: Int): String {
        val name = file.name
        val idx = extractImageIndex(name)
        val wavelength = idx?.let { amsiWavelengths.getOrNull(it) }
        return when {
            Regex("(?i)cal_dark_\\d+").containsMatchIn(name) ->
                wavelength?.let { "Dark\n$it nm" } ?: "Dark\n${idx ?: frameIdx + 1}"
            Regex("(?i)cal_image_\\d+").containsMatchIn(name) ->
                wavelength?.let { "$it nm" } ?: "Cal\n${idx ?: frameIdx + 1}"
            Regex("(?i)image_\\d+").containsMatchIn(name) ->
                wavelength?.let { "$it nm" } ?: "Image\n${idx ?: frameIdx + 1}"
            else -> "Frame\n${frameIdx + 1}"
        }
    }

    private fun extractImageIndex(name: String): Int? {
        val match = Regex("(?i)(?:cal_image_|cal_dark_|image_|cal_channel_|frame_)(\\d+)").find(name)
            ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
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
