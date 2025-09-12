package com.example.msiandroidapp.ui.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.ui.control.ControlViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GalleryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultsAdapter

    private val galleryViewModel: GalleryViewModel by viewModels()
    private val controlViewModel: ControlViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())

    // Selection state (sessions + calibrations share one set of IDs)
    private val selectedIds = linkedSetOf<Long>()
    private var inSelectionMode = false

    // Exact AMSI order (0..15)
    private val AMSI_WAVELENGTHS = intArrayOf(
        395, 415, 450, 470, 505, 528, 555, 570, 590, 610, 625, 640, 660, 730, 850, 880
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_gallery, container, false)
        recyclerView = root.findViewById(R.id.gallery_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Adapter supports selection visuals for both item types
        adapter = ResultsAdapter(
            onSessionClick = { s ->
                if (inSelectionMode) toggleSelection(idForSession(s)) else openSession(s)
            },
            onSessionLongClick = { s ->
                if (!inSelectionMode) enterSelectionMode()
                toggleSelection(idForSession(s))
            },
            onCalibrationClick = { c ->
                if (inSelectionMode) toggleSelection(idForCalibration(c)) else openCalibration(c)
            },
            onCalibrationLongClick = { c ->
                if (!inSelectionMode) enterSelectionMode()
                toggleSelection(idForCalibration(c))
            }
        )
        recyclerView.adapter = adapter
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Merge in-progress strip + persisted results
        galleryViewModel.results.observe(viewLifecycleOwner) { list ->
            submitMerged(list)
            invalidateSelectionUI()
        }

        // Rebuild the in-progress row as capture progresses
        listOf(
            controlViewModel.isCapturing,
            controlViewModel.capturedBitmaps,
            controlViewModel.imageCount
        ).forEach { live ->
            live.observe(viewLifecycleOwner) {
                submitMerged(galleryViewModel.results.value ?: emptyList())
            }
        }
    }

    // -------------------- Toolbar menu --------------------
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Use your selection menu XML (with select_all/share/delete/cancel)
        inflater.inflate(R.menu.menu_gallery_selection, menu)
        // Visibility toggles
        menu.findItem(R.id.action_select_all)?.isVisible = true
        menu.findItem(R.id.action_share_selected)?.isVisible = inSelectionMode
        menu.findItem(R.id.action_delete_selected)?.isVisible = inSelectionMode
        menu.findItem(R.id.action_cancel_selection)?.isVisible = inSelectionMode
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_select_all       -> { selectAllVisible(); true }
        R.id.action_share_selected   -> { shareSelected(); true }
        R.id.action_delete_selected  -> { deleteSelected(); true }
        R.id.action_cancel_selection -> { exitSelectionMode(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // -------------------- Selection helpers --------------------
    private fun idForSession(s: Session) = s.id
    private fun idForCalibration(p: CalibrationProfile) = 1_000_000_000_000L + p.id

    private fun enterSelectionMode() {
        inSelectionMode = true
        adapter.setSelectionMode(true)
        requireActivity().invalidateOptionsMenu()
    }

    private fun exitSelectionMode() {
        inSelectionMode = false
        selectedIds.clear()
        adapter.setSelectionMode(false)
        adapter.updateSelection(selectedIds)
        requireActivity().invalidateOptionsMenu()
    }

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        adapter.updateSelection(selectedIds)
        invalidateSelectionUI()
        if (selectedIds.isEmpty()) exitSelectionMode()
    }

    private fun selectAllVisible() {
        val all = adapter.currentList
            .filter { it !is ResultListItem.InProgress }
            .map {
                when (it) {
                    is ResultListItem.SessionItem     -> idForSession(it.session)
                    is ResultListItem.CalibrationItem -> idForCalibration(it.profile)
                    else -> error("unreachable")
                }
            }
            .toSet()
        if (all.isNotEmpty() && !inSelectionMode) enterSelectionMode()
        selectedIds.clear()
        selectedIds.addAll(all)
        adapter.updateSelection(selectedIds)
        invalidateSelectionUI()
    }

    private fun invalidateSelectionUI() {
        requireActivity().invalidateOptionsMenu()
    }

    private fun reconcileSelectionWith(list: List<ResultListItem>) {
        if (selectedIds.isEmpty()) return
        val valid = list.filter { it !is ResultListItem.InProgress }.map {
            when (it) {
                is ResultListItem.SessionItem     -> idForSession(it.session)
                is ResultListItem.CalibrationItem -> idForCalibration(it.profile)
                else -> error("unreachable")
            }
        }.toSet()
        val changed = selectedIds.removeAll { it !in valid }
        if (changed) adapter.updateSelection(selectedIds)
        if (selectedIds.isEmpty()) exitSelectionMode()
    }

    // -------------------- In-progress header --------------------
    private var hideInProgressOnce = false

    private fun buildInProgressItemOrNull(): ResultListItem.InProgress? {
        val isCapturing = controlViewModel.isCapturing.value ?: false
        val bitmaps = controlViewModel.capturedBitmaps.value ?: emptyList()
        val imageCount = controlViewModel.imageCount.value ?: 0
        return if ((isCapturing || imageCount < 16) && bitmaps.any { it != null }) {
            ResultListItem.InProgress(bitmaps, imageCount)
        } else if (imageCount == 16 && bitmaps.any { it != null }) {
            handler.postDelayed({
                submitMerged(galleryViewModel.results.value ?: emptyList(), forceHideInProgress = true)
            }, 1500)
            ResultListItem.InProgress(bitmaps, imageCount)
        } else null
    }

    private fun submitMerged(list: List<ResultListItem>, forceHideInProgress: Boolean = false) {
        if (forceHideInProgress) hideInProgressOnce = true
        val merged = mutableListOf<ResultListItem>()
        val inProg = if (!hideInProgressOnce) buildInProgressItemOrNull() else null
        if (inProg != null) merged.add(inProg)
        merged.addAll(list)
        adapter.submitList(merged)
        reconcileSelectionWith(merged)
    }

    // -------------------- Open actions --------------------
    private fun openSession(session: Session) {
        // Launch your existing detail screen
        startActivity(SessionDetailActivity.newIntent(requireContext(), session.id))
    }

    private fun openCalibration(profile: CalibrationProfile) {
        val wlToNorm = extractCalibrationMap(profile)
        val whenStr = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(java.util.Date(profile.createdAt))
        }.getOrElse { "—" }

        val header = buildString {
            appendLine("Calibration Profile")
            appendLine("-------------------")
            appendLine("Name: ${profile.name ?: "—"}")
            appendLine("When: $whenStr")
            if (!profile.summary.isNullOrBlank()) appendLine(profile.summary)
            appendLine()
            appendLine("Wavelength (nm)    Norm")
            appendLine("------------------------")
        }
        val body = buildString {
            AMSI_WAVELENGTHS.forEach { wl ->
                val norm = wlToNorm[wl]
                val normStr = norm?.let { String.format(Locale.US, "%.6f", it) } ?: "—"
                appendLine(String.format(Locale.US, "%-16s %8s", "${wl}nm", normStr))
            }
        }

        val tv = TextView(requireContext()).apply {
            text = header + body
            setPadding(48, 32, 48, 16)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Calibration Results")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("Calibration Results", tv.text)
                )
            }
            .show()
    }

    // -------------------- Share / Delete --------------------
    private fun shareSelected() {
        val current = adapter.currentList
        val chosen = selectedIds.toSet()

        val sessions = mutableListOf<Session>()
        val calibs   = mutableListOf<CalibrationProfile>()

        current.forEach {
            when (it) {
                is ResultListItem.SessionItem ->
                    if (chosen.contains(idForSession(it.session)) && it.session.imagePaths.size >= 16)
                        sessions.add(it.session)
                is ResultListItem.CalibrationItem ->
                    if (chosen.contains(idForCalibration(it.profile)))
                        calibs.add(it.profile)
                else -> Unit
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uris = arrayListOf<Uri>()
                if (sessions.isNotEmpty()) buildSessionsZip(sessions)?.let { uris.add(fileUri(it)) }
                calibs.forEach { makeCalibrationJsonFile(it)?.let { f -> uris.add(fileUri(f)) } }

                if (uris.isEmpty()) {
                    launch(Dispatchers.Main) { msg("Nothing to share. Select at least one complete session or calibration.") }
                    return@launch
                }

                launch(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/octet-stream"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share results"))
                    exitSelectionMode()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { msg("Share failed: ${e.message}") }
            }
        }
    }

    private fun deleteSelected() {
        val current = adapter.currentList
        val chosen = selectedIds.toSet()

        val sessions = mutableListOf<Session>()
        val calibs   = mutableListOf<CalibrationProfile>()

        current.forEach {
            when (it) {
                is ResultListItem.SessionItem ->
                    if (chosen.contains(idForSession(it.session))) sessions.add(it.session)
                is ResultListItem.CalibrationItem ->
                    if (chosen.contains(idForCalibration(it.profile))) calibs.add(it.profile)
                else -> Unit
            }
        }

        if (sessions.isEmpty() && calibs.isEmpty()) {
            msg("No items selected.")
            return
        }

        val title = buildString {
            append("Delete ")
            if (sessions.isNotEmpty()) append("${sessions.size} session(s)")
            if (sessions.isNotEmpty() && calibs.isNotEmpty()) append(" and ")
            if (calibs.isNotEmpty()) append("${calibs.size} calibration(s)")
            append("?")
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage("This permanently removes the selected records (and session images).")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(requireContext().applicationContext)
                    val sDao = db.sessionDao()
                    val cDao = db.calibrationDao()

                    // Delete sessions + their files
                    for (s in sessions) {
                        s.imagePaths.forEach { path -> runCatching { File(path).delete() } }
                        runCatching { sDao.delete(s) }
                    }
                    // Delete calibration profiles
                    for (c in calibs) {
                        runCatching { cDao.delete(c) }
                    }

                    launch(Dispatchers.Main) { exitSelectionMode() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------- Sessions ZIP + EXIF --------------------
    private fun buildSessionsZip(sessions: List<Session>): File? = runCatching {
        val cacheDir = File(requireContext().cacheDir, "shares").apply { mkdirs() }
        val zipFile = File(cacheDir, "sessions_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sessions.forEach { s ->
                val folder = buildFolderName(s)
                s.imagePaths.take(16).forEachIndexed { idx, path ->
                    val src = File(path)
                    if (!src.exists()) return@forEachIndexed

                    val tempCopy = File(cacheDir, buildImageFileNameForShare(s, idx))
                    stampExifToCopy(src, tempCopy, s, idx)

                    FileInputStream(tempCopy).use { fis ->
                        zos.putNextEntry(ZipEntry("$folder/${tempCopy.name}"))
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                    tempCopy.delete()
                }
            }
        }
        zipFile
    }.getOrNull()

    // -------------------- Calibration JSON --------------------
    private fun makeCalibrationJsonFile(profile: CalibrationProfile): File? = runCatching {
        val dir = File(requireContext().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "calibration_${profile.id}.json")

        val wlToNorm = extractCalibrationMap(profile)
        val root = JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("summary", profile.summary)
            put("createdAt", profile.createdAt)
            // map of wavelength->norm
            val normsObj = JSONObject()
            wlToNorm.toSortedMap().forEach { (wl, v) -> normsObj.put(wl.toString(), v) }
            put("norms", normsObj)
            // raw array for round-trip back to Pi
            val arr = JSONArray()
            (profile.ledNorms ?: emptyList()).forEach { arr.put(it) }
            put("led_norms", arr)
        }

        FileOutputStream(file).use { it.write(root.toString(2).toByteArray()) }
        file
    }.getOrNull()

    // -------------------- EXIF / naming helpers --------------------
    private fun wavelengthForIndex(indexInSession: Int): Int? =
        AMSI_WAVELENGTHS.getOrNull(indexInSession)

    private fun parseSessionDate(sessionTimestamp: String): java.util.Date {
        val fmts = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "dd/MM/yyyy HH:mm:ss",
            "EEE MMM dd HH:mm:ss zzz yyyy"
        )
        return fmts.firstNotNullOfOrNull { fmt ->
            runCatching { SimpleDateFormat(fmt, Locale.US).parse(sessionTimestamp) }.getOrNull()
        } ?: java.util.Date()
    }

    private fun datePartsForName(session: Session): Pair<String, String> {
        val dt = parseSessionDate(session.timestamp)
        val d = SimpleDateFormat("dd_MM_yyyy", Locale.US).format(dt)   // DD_MM_YYYY
        val t = SimpleDateFormat("HH_mm_ss", Locale.US).format(dt)     // HH_mm_ss
        return d to t
    }

    private fun buildFolderName(session: Session): String {
        val (d, t) = datePartsForName(session)
        val loc = session.location?.trim().takeUnless { it.isNullOrEmpty() } ?: "UnknownLoc"
        val safeLoc = loc.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").replace("\\s+".toRegex(), "_")
        return "Date_${d}___Time_${t}___Location_${safeLoc}"
    }

    private fun buildImageFileNameForShare(session: Session, index: Int): String {
        val wl = wavelengthForIndex(index)
        return if (wl != null) "Wavelength_${wl}nm.jpg" else "Wavelength_unknown.jpg"
    }

    private fun stampExifToCopy(src: File, dst: File, session: Session, indexInSession: Int) {
        // Copy original, then try writing EXIF (best effort)
        FileInputStream(src).use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
        try {
            val exif = ExifInterface(dst.absolutePath)
            val dt = parseSessionDate(session.timestamp)
            val exifFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val dateStr = exifFormat.format(dt)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)

            val wl = wavelengthForIndex(indexInSession)
            val description = buildString {
                append("MSI capture; Session ${session.id}; ")
                append("${session.timestamp}; ")
                append("Location: ${session.location ?: "Unknown"}")
                if (wl != null) append("; Wavelength: ${wl}nm")
            }
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
            exif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "Session=${session.id}; Time=${session.timestamp}; Location=${session.location ?: "Unknown"};" +
                        (wl?.let { " Wavelength=${it}nm" } ?: "")
            )
            exif.saveAttributes()
        } catch (_: Exception) {
            // If file isn't JPEG or EXIF write failed, leave the copy as-is
        }
    }

    // -------------------- Calibration helpers --------------------
    private fun extractCalibrationMap(profile: CalibrationProfile): Map<Int, Double> {
        val list = profile.ledNorms
        return if (list != null && list.size == AMSI_WAVELENGTHS.size) {
            AMSI_WAVELENGTHS.indices.associate { i -> AMSI_WAVELENGTHS[i] to list[i] }
        } else emptyMap()
    }

    private fun fileUri(file: File): Uri =
        FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )

    private fun msg(text: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(text).setPositiveButton("OK", null).show()
    }
}
