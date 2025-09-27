package com.example.msiandroidapp.ui.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.ui.control.ControlViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ---- NEW: filter model ----
    private enum class ResultsFilter { ALL, SESSIONS, CALIBRATIONS }
    private var currentFilter: ResultsFilter = ResultsFilter.ALL
    private val STATE_FILTER_KEY = "results_filter"

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultsAdapter

    private val galleryViewModel: GalleryViewModel by viewModels()
    private val controlViewModel: ControlViewModel by activityViewModels()

    private val handler = Handler(Looper.getMainLooper())

    // Selection state (owned by Fragment)
    private val selectedIds = linkedSetOf<Long>()
    private var inSelectionMode = false

    // Back press callback (enabled only when there’s a selection)
    private lateinit var backCallback: OnBackPressedCallback

    // Cached toolbar items
    private var menuItemRenameInline: MenuItem? = null
    private var filterAllItem: MenuItem? = null
    private var filterSessionsItem: MenuItem? = null
    private var filterCalibsItem: MenuItem? = null

    private val AMSI_WAVELENGTHS = intArrayOf(
        395, 415, 450, 470, 505, 528, 555, 570, 590, 610, 625, 640, 660, 730, 850, 880
    )

    // Local persistence for custom names
    private val prefs by lazy {
        requireContext().getSharedPreferences("gallery_custom_names", Context.MODE_PRIVATE)
    }
    private fun spKeySession(id: Long) = "session_name_$id"
    private fun spKeyCalib(id: Long) = "calib_name_$id"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        // restore filter
        currentFilter = savedInstanceState?.getString(STATE_FILTER_KEY)?.let {
            runCatching { ResultsFilter.valueOf(it) }.getOrNull()
        } ?: ResultsFilter.ALL

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                clearSelectionAndExitMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, backCallback)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FILTER_KEY, currentFilter.name)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_gallery, container, false)

        recyclerView = root.findViewById(R.id.gallery_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

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
            },
            displayNameProvider = object : DisplayNameProvider {
                override fun titleFor(session: Session): String =
                    this@GalleryFragment.displayNameFor(session)

                override fun subtitleFor(session: Session): String =
                    this@GalleryFragment.subtitleFor(session)

                override fun titleFor(cal: CalibrationProfile): String =
                    this@GalleryFragment.displayNameFor(cal)
            }
        ).apply {
            onSelectionChanged = { hasSelection, _ ->
                backCallback.isEnabled = hasSelection
                invalidateSelectionUI()
            }
        }

        recyclerView.adapter = adapter
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        galleryViewModel.results.observe(viewLifecycleOwner) { list ->
            // Apply filter + newest-first sort before showing
            val filteredSorted = applyFilterAndSort(list)
            submitMerged(filteredSorted)
            reconcileSelectionWith(adapter.currentList)
        }

        // Rebuild in-progress row when capture state changes
        listOf(
            controlViewModel.isCapturing,
            controlViewModel.capturedBitmaps,
            controlViewModel.imageCount
        ).forEach { live ->
            live.observe(viewLifecycleOwner) {
                val base = galleryViewModel.results.value ?: emptyList()
                submitMerged(applyFilterAndSort(base))
            }
        }
    }

    // ---------- Toolbar ----------
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_gallery_selection, menu)

        // selection actions
        menuItemRenameInline = menu.findItem(R.id.action_rename_inline)

        // filter submenu items (added to the same menu XML below)
        filterAllItem = menu.findItem(R.id.filter_all)
        filterSessionsItem = menu.findItem(R.id.filter_sessions)
        filterCalibsItem = menu.findItem(R.id.filter_calibrations)

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val share  = menu.findItem(R.id.action_share_selected)
        val delete = menu.findItem(R.id.action_delete_selected)
        val cancel = menu.findItem(R.id.action_cancel_selection)

        // Show selection actions only when in selection mode
        share?.isVisible  = inSelectionMode
        delete?.isVisible = inSelectionMode
        cancel?.isVisible = inSelectionMode

        // Show inline Rename ONLY when exactly one item is selected
        menuItemRenameInline?.isVisible = inSelectionMode && selectedIds.size == 1

        // Update checkmarks on filter menu
        filterAllItem?.isChecked = currentFilter == ResultsFilter.ALL
        filterSessionsItem?.isChecked = currentFilter == ResultsFilter.SESSIONS
        filterCalibsItem?.isChecked = currentFilter == ResultsFilter.CALIBRATIONS

        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        // selection actions
        R.id.action_select_all       -> { selectAllVisible(); true }
        R.id.action_share_selected   -> { shareSelected(); true }
        R.id.action_delete_selected  -> { deleteSelected(); true }
        R.id.action_cancel_selection -> { clearSelectionAndExitMode(); true }
        R.id.action_rename_inline    -> { promptRenameSelected(); true }

        // filter actions
        R.id.filter_all -> {
            setFilter(ResultsFilter.ALL)
            item.isChecked = true
            true
        }
        R.id.filter_sessions -> {
            setFilter(ResultsFilter.SESSIONS)
            item.isChecked = true
            true
        }
        R.id.filter_calibrations -> {
            setFilter(ResultsFilter.CALIBRATIONS)
            item.isChecked = true
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun setFilter(f: ResultsFilter) {
        if (currentFilter == f) return
        currentFilter = f
        // Re-apply to current data
        val base = galleryViewModel.results.value ?: emptyList()
        submitMerged(applyFilterAndSort(base), forceHideInProgress = true)
        // Exit selection mode because visible set changed
        clearSelectionAndExitMode()
    }

    private fun invalidateSelectionUI() {
        requireActivity().invalidateOptionsMenu()
    }

    // ---------- Filtering + newest-first sorting ----------
    private fun applyFilterAndSort(list: List<ResultListItem>): List<ResultListItem> {
        // Drop InProgress here; that row is handled separately by submitMerged()
        val filtered = when (currentFilter) {
            ResultsFilter.ALL -> list.filter { it !is ResultListItem.InProgress }
            ResultsFilter.SESSIONS -> list.filter { it is ResultListItem.SessionItem }
            ResultsFilter.CALIBRATIONS -> list.filter { it is ResultListItem.CalibrationItem }
        }

        // Sort newest-first using best available timestamp
        return filtered.sortedByDescending { item ->
            when (item) {
                is ResultListItem.SessionItem -> sortTimestampForSession(item.session)
                is ResultListItem.CalibrationItem -> item.profile.createdAt
                is ResultListItem.InProgress -> Long.MIN_VALUE // not used; filtered out
            }
        }
    }

    private fun sortTimestampForSession(session: Session): Long {
        // Prefer a parsed timestamp if available; else fall back to file lastModified
        val parsed = runCatching { parseSessionDate(session.timestamp).time }.getOrNull()
        if (parsed != null) return parsed

        // If you have imagePaths, use the newest image's lastModified as a fallback
        val newest = session.imagePaths.maxOfOrNull { path -> File(path).lastModified() }
        return newest ?: 0L
    }

    // ---------- Selection ----------
    private fun idForSession(s: Session) = s.id
    private fun idForCalibration(p: CalibrationProfile) = 1_000_000_000_000L + p.id
    private fun isCalibrationId(id: Long) = id >= 1_000_000_000_000L
    private fun calibIdFromSelId(id: Long) = id - 1_000_000_000_000L

    private fun enterSelectionMode() {
        inSelectionMode = true
        adapter.setSelectionMode(true)
        invalidateSelectionUI()
        backCallback.isEnabled = true
    }

    private fun clearSelectionAndExitMode() {
        inSelectionMode = false
        selectedIds.clear()
        adapter.clearSelection()
        invalidateSelectionUI()
        backCallback.isEnabled = false
    }

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        adapter.updateSelection(selectedIds)
        invalidateSelectionUI()
        if (selectedIds.isEmpty()) clearSelectionAndExitMode()
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
        if (selectedIds.isEmpty()) clearSelectionAndExitMode()
    }

    // ---------- In-progress ----------
    private var hideInProgressOnce = false

    private fun buildInProgressItemOrNull(): ResultListItem.InProgress? {
        val isCapturing = controlViewModel.isCapturing.value ?: false
        val bitmaps = controlViewModel.capturedBitmaps.value ?: emptyList()
        val imageCount = controlViewModel.imageCount.value ?: 0

        return if ((isCapturing || imageCount < 16) && bitmaps.any { it != null }) {
            ResultListItem.InProgress(bitmaps, imageCount)
        } else if (imageCount == 16 && bitmaps.any { it != null }) {
            handler.postDelayed({
                val base = galleryViewModel.results.value ?: emptyList()
                submitMerged(applyFilterAndSort(base), forceHideInProgress = true)
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

    // ---------- Open ----------
    private fun openSession(session: Session) {
        startActivity(SessionDetailActivity.newIntent(requireContext(), session.id))
    }

    private fun openCalibration(profile: CalibrationProfile) {
        val wlToNorm = extractCalibrationMap(profile)
        val whenStr = runCatching {
            SimpleDateFormat("dd-MM-yyyy | HH:mm", Locale.getDefault())
                .format(java.util.Date(profile.createdAt))
        }.getOrElse { "—" }

        val header = buildString {
            appendLine("Calibration Profile")
            appendLine("-------------------")
            appendLine("Name: ${displayNameFor(profile)}")
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
                    .getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("Calibration Results", tv.text)
                )
            }
            .show()
    }

    // ---------- Rename ----------
    private fun promptRenameSelected() {
        if (selectedIds.size != 1) return
        val sel = selectedIds.first()
        val isCal = isCalibrationId(sel)

        val (currentTitle, setter) = if (isCal) {
            val id = calibIdFromSelId(sel)
            val existing = currentCalibrationById(id)?.let { displayNameFor(it) }
                ?: prefs.getString(spKeyCalib(id), "") ?: ""
            existing to { newName: String -> renameCalibration(id, newName) }
        } else {
            val id = sel
            val session = currentSessionById(id)
            val existing = displayNameFor(session)
            existing to { newName: String -> renameSession(id, newName) }
        }

        val input = android.widget.EditText(requireContext()).apply {
            hint = "Enter new name"
            setSingleLine(true)
            setText(currentTitle)
            setSelection(text.length)
        }

        val dlg = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(if (isCal) "Rename Calibration" else "Rename Session")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dlg.setOnShowListener {
            input.post {
                input.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    input.error = "Name cannot be empty"
                    return@setOnClickListener
                }
                if (name.length > 64) {
                    input.error = "Keep it under 64 characters"
                    return@setOnClickListener
                }
                setter(name)
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun renameSession(sessionId: Long, newName: String) {
        prefs.edit().putString(spKeySession(sessionId), newName).apply()
        refreshDisplayNameForId(sessionId)
        msg("Session renamed.")
    }

    private fun renameCalibration(calibId: Long, newName: String) {
        prefs.edit().putString(spKeyCalib(calibId), newName).apply()
        refreshDisplayNameForId(1_000_000_000_000L + calibId)
        msg("Calibration renamed.")
    }

    // ---------- Share / Delete ----------
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

                withContext(Dispatchers.Main) {
                    if (uris.isEmpty()) {
                        msg("Nothing to share. Select at least one complete session or calibration.")
                        return@withContext
                    }
                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/octet-stream"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share results"))
                    clearSelectionAndExitMode()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { msg("Share failed: ${e.message}") }
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
                    for (s in sessions) {
                        s.imagePaths.forEach { path -> runCatching { File(path).delete() } }
                        prefs.edit().remove(spKeySession(s.id)).apply()
                    }
                    for (c in calibs) {
                        prefs.edit().remove(spKeyCalib(c.id)).apply()
                    }
                    withContext(Dispatchers.Main) { clearSelectionAndExitMode() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- ZIP / EXIF ----------
    private fun buildSessionsZip(sessions: List<Session>): File? = runCatching {
        val cacheDir = File(requireContext().cacheDir, "shares").apply { mkdirs() }
        val zipFile = File(cacheDir, "sessions_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sessions.forEach { s ->
                val folder = buildFolderName(s)
                s.imagePaths.take(16).forEachIndexed { idx, path ->
                    val src = File(path)
                    if (!src.exists()) return@forEachIndexed

                    val tempCopy = File(cacheDir, buildImageFileNameForShare(idx))
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

    private fun buildImageFileNameForShare(index: Int): String {
        val wl = wavelengthForIndex(index)
        return if (wl != null) "Wavelength_${wl}nm.png" else "Wavelength_unknown.png"
    }

    private fun makeCalibrationJsonFile(profile: CalibrationProfile): File? = runCatching {
        val dir = File(requireContext().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "calibration_${profile.id}.json")

        val wlToNorm = extractCalibrationMap(profile)
        val root = JSONObject().apply {
            put("id", profile.id)
            put("name", displayNameFor(profile))
            put("summary", profile.summary)
            put("createdAt", profile.createdAt)
            val normsObj = JSONObject()
            wlToNorm.toSortedMap().forEach { (wl, v) -> normsObj.put(wl.toString(), v) }
            put("norms", normsObj)
            val arr = JSONArray()
            (profile.ledNorms ?: emptyList()).forEach { arr.put(it) }
            put("led_norms", arr)
        }

        FileOutputStream(file).use { it.write(root.toString(2).toByteArray()) }
        file
    }.getOrNull()

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
        val d = SimpleDateFormat("dd_MM_yyyy", Locale.US).format(dt)
        val t = SimpleDateFormat("HH_mm_ss", Locale.US).format(dt)
        return d to t
    }

    private fun buildFolderName(session: Session): String {
        val (d, t) = datePartsForName(session)
        val loc = (session.location ?: "UnknownLoc").trim()
        val custom = prefs.getString(spKeySession(session.id), "")?.trim().orEmpty()
        val title = if (custom.isNotEmpty()) sanitizeName(custom) else sanitizeName(defaultSessionTitle(session))
        return "${title}___Date_${d}___Time_${t}___Location_${sanitizeName(loc)}"
    }

    private fun stampExifToCopy(src: File, dst: File, session: Session, indexInSession: Int) {
        // Copy original bytes to the .png-named temp file
        FileInputStream(src).use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }

        // Attach metadata (supported for PNG via XMP in ExifInterface)
        try {
            val exif = ExifInterface(dst.absolutePath)
            val dt = parseSessionDate(session.timestamp)
            val exifFormat = SimpleDateFormat("dd-MM-yyyy | HH:mm:ss", Locale.US)
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
        } catch (_: Exception) { }
    }


    private fun displayNameFor(session: Session?): String {
        if (session == null) return "Session ${System.currentTimeMillis()}"
        val overridden = prefs.getString(spKeySession(session.id), "")?.trim().orEmpty()
        return if (overridden.isNotEmpty()) overridden else defaultSessionTitle(session)
    }

    private fun displayNameFor(profile: CalibrationProfile): String {
        val local = prefs.getString(spKeyCalib(profile.id), "")?.trim().orEmpty()
        val dbName = (profile.name ?: "").trim()
        return when {
            local.isNotEmpty() -> local
            dbName.isNotEmpty() -> dbName
            else -> "Calibration ${profile.id}"
        }
    }

    private fun subtitleFor(session: Session): String {
        val latLon = (session.location ?: "Unknown").trim()
        val dt = parseSessionDate(session.timestamp)
        val ts = SimpleDateFormat("| dd-MM-yyyy | HH:mm:ss", Locale.getDefault()).format(dt)
        return "$latLon — $ts"
    }

    private fun defaultSessionTitle(session: Session): String = "Session ${session.id}"

    private fun sanitizeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").replace("\\s+".toRegex(), "_")

    private fun extractCalibrationMap(profile: CalibrationProfile): Map<Int, Double> {
        val list = profile.ledNorms
        return if (list != null && list.size == AMSI_WAVELENGTHS.size) {
            AMSI_WAVELENGTHS.indices.associate { i -> AMSI_WAVELENGTHS[i] to list[i] }
        } else emptyMap()
    }

    private fun refreshDisplayNameForId(selId: Long) {
        val index = adapter.currentList.indexOfFirst { item ->
            when (item) {
                is ResultListItem.SessionItem     -> item.session.id == selId
                is ResultListItem.CalibrationItem -> 1_000_000_000_000L + item.profile.id == selId
                else -> false
            }
        }
        if (index >= 0) {
            adapter.notifyItemChanged(index, ResultsAdapter.PAYLOAD_TITLE_CHANGED)
        }
    }

    private fun currentSessionById(id: Long): Session? =
        adapter.currentList.firstOrNull {
            it is ResultListItem.SessionItem && it.session.id == id
        }?.let { (it as ResultListItem.SessionItem).session }

    private fun currentCalibrationById(id: Long): CalibrationProfile? =
        adapter.currentList.firstOrNull {
            it is ResultListItem.CalibrationItem && it.profile.id == id
        }?.let { (it as ResultListItem.CalibrationItem).profile }

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

interface DisplayNameProvider {
    fun titleFor(session: Session): String
    fun subtitleFor(session: Session): String
    fun titleFor(cal: CalibrationProfile): String
}
