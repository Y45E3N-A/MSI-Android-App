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
import androidx.core.content.ContextCompat
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
import java.util.Date

class GalleryFragment : Fragment() {

    // ---- filter model ----
    private enum class ResultsFilter { ALL, AMSI, PMFI, CALIBRATIONS }
    private var currentFilter: ResultsFilter = ResultsFilter.ALL
    private val STATE_FILTER_KEY = "results_filter"

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultsAdapter

    private val galleryViewModel: GalleryViewModel by viewModels()
    private val controlViewModel: ControlViewModel by activityViewModels()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    private lateinit var emptyState: View
    private lateinit var chipAll: com.google.android.material.chip.Chip
    private lateinit var chipAmsi: com.google.android.material.chip.Chip
    private lateinit var chipPmfi: com.google.android.material.chip.Chip
    private lateinit var chipCalib: com.google.android.material.chip.Chip
    private lateinit var btnSort: com.google.android.material.button.MaterialButton

    // Sorting toggle: true = newest first, false = oldest first
    private var newestFirst: Boolean = true

    // Selection state
    private val selectedIds = linkedSetOf<Long>()
    private var inSelectionMode = false

    // Back press callback
    private lateinit var backCallback: OnBackPressedCallback

    // Cached toolbar items

    private val AMSI_WAVELENGTHS = intArrayOf(
        395, 415, 450, 470, 505, 528, 555, 570, 590, 610, 625, 640, 660, 730, 850, 880
    )

    // Local persistence for custom names
    private val prefs by lazy {
        requireContext().getSharedPreferences("gallery_custom_names", Context.MODE_PRIVATE)
    }
    private fun spKeySession(id: Long) = "session_name_$id"
    private fun spKeyCalib(runId: String) = "calib_name_$runId"
    // Returns a stable non-null Long for a calibration row.
// If the row hasn't been assigned an autoincrement ID yet (id == null),
// we fall back to a hash of runId shifted into a high range so it won't
// collide with session IDs.
    private fun stableCalibId(p: CalibrationProfile): Long {
        return p.runId.hashCode().toLong() + 1_000_000_000_000L
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

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

        toolbar = root.findViewById(R.id.gallery_toolbar)
        recyclerView = root.findViewById(R.id.gallery_recycler_view)
        emptyState = root.findViewById(R.id.empty_state)

        chipAll = root.findViewById(R.id.chip_all)
        chipAmsi = root.findViewById(R.id.chip_amsi)
        chipPmfi = root.findViewById(R.id.chip_pmfi)
        chipCalib = root.findViewById(R.id.chip_calib)
        btnSort = root.findViewById(R.id.btn_sort)

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
                override fun titleFor(session: Session) =
                    this@GalleryFragment.displayNameFor(session)

                override fun subtitleFor(session: Session) =
                    this@GalleryFragment.subtitleFor(session)

                override fun titleFor(cal: CalibrationProfile) =
                    this@GalleryFragment.displayNameFor(cal)

                override fun subtitleFor(cal: CalibrationProfile) =
                    this@GalleryFragment.subtitleFor(cal)
            }

        ).apply {
            onSelectionChanged = { hasSelection, count ->
                backCallback.isEnabled = hasSelection
                updateToolbarForSelection(count)
            }
        }

        recyclerView.adapter = adapter

        // Header actions
        toolbar.setOnMenuItemClickListener { item -> onTopMenuItem(item) }

        chipAll.setOnClickListener { setFilter(ResultsFilter.ALL); updateChips() }
        chipAmsi.setOnClickListener { setFilter(ResultsFilter.AMSI); updateChips() }
        chipPmfi.setOnClickListener { setFilter(ResultsFilter.PMFI); updateChips() }
        chipCalib.setOnClickListener { setFilter(ResultsFilter.CALIBRATIONS); updateChips() }
        updateChips()

        btnSort.setOnClickListener {
            newestFirst = !newestFirst
            btnSort.text = if (newestFirst) "Sort: Newest" else "Sort: Oldest"
            val base = galleryViewModel.results.value ?: emptyList()
            submitMerged(applyFilterAndSort(base), forceHideInProgress = true)
        }
        btnSort.text = if (newestFirst) "Sort: Newest" else "Sort: Oldest"

        return root
    }
    private fun applyFilterAndSort(list: List<ResultListItem>): List<ResultListItem> {
        val filtered = when (currentFilter) {
            ResultsFilter.ALL -> list.filter { it !is ResultListItem.InProgress }
            ResultsFilter.AMSI -> list.filter { item ->
                when (item) {
                    is ResultListItem.SessionItem     -> item.session.isAMSI()
                    is ResultListItem.CalibrationItem -> false
                    is ResultListItem.InProgress      -> false
                }
            }
            ResultsFilter.PMFI -> list.filter { item ->
                when (item) {
                    is ResultListItem.SessionItem     -> item.session.isPMFI()
                    is ResultListItem.CalibrationItem -> false
                    is ResultListItem.InProgress      -> false
                }
            }
            ResultsFilter.CALIBRATIONS -> list.filter { it is ResultListItem.CalibrationItem }
        }

        // deterministically sort by (timestampKey, stableId)
        val asc = filtered.sortedWith(
            compareBy<ResultListItem>(
                { sortKeyForItem(it) },
                { stableIdForItem(it) },
            )
        )

        // newestFirst means descending timestamp
        return if (newestFirst) asc.reversed() else asc
    }



    private fun onTopMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_select_all       -> { selectAllVisible(); true }
        R.id.action_share_selected   -> { shareSelected(); true }
        R.id.action_delete_selected  -> { deleteSelected(); true }
        R.id.action_rename_inline    -> { promptRenameSelected(); true }
        else -> false
    }

    private fun updateToolbarForSelection(selectedCount: Int) {
        inSelectionMode = selectedCount > 0
        val menu = toolbar.menu

        // Title reflects mode
        toolbar.title = if (inSelectionMode) "$selectedCount selected" else "MFi Android App v3.1"



        // Visibility of actions in header menu
        menu.findItem(R.id.action_share_selected)?.isVisible = inSelectionMode
        menu.findItem(R.id.action_delete_selected)?.isVisible = inSelectionMode
        menu.findItem(R.id.action_select_all)?.isVisible = !inSelectionMode || selectedCount < adapter.currentList.count {
            it !is ResultListItem.InProgress
        }
        menu.findItem(R.id.action_rename_inline)?.isVisible = inSelectionMode && selectedIds.size == 1
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        galleryViewModel.results.observe(viewLifecycleOwner) { list ->
            val filteredSorted = applyFilterAndSort(list)
            submitMerged(filteredSorted)
            reconcileSelectionWith(adapter.currentList)
        }

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
        menuItemRenameInline = menu.findItem(R.id.action_rename_inline)

        // NEW filter items
        filterAllItem     = menu.findItem(R.id.filter_all)
        filterAmsiItem    = menu.findItem(R.id.filter_amsi)
        filterPmfiItem    = menu.findItem(R.id.filter_pmfi)
        filterCalibsItem  = menu.findItem(R.id.filter_calibrations)

        super.onCreateOptionsMenu(menu, inflater)
    }


    override fun onPrepareOptionsMenu(menu: Menu) {
        val share  = menu.findItem(R.id.action_share_selected)
        val delete = menu.findItem(R.id.action_delete_selected)
        val cancel = menu.findItem(R.id.action_cancel_selection)

        share?.isVisible  = inSelectionMode
        delete?.isVisible = inSelectionMode
        cancel?.isVisible = inSelectionMode
        menuItemRenameInline?.isVisible = inSelectionMode && selectedIds.size == 1

        filterAllItem?.isChecked    = currentFilter == ResultsFilter.ALL
        filterAmsiItem?.isChecked   = currentFilter == ResultsFilter.AMSI
        filterPmfiItem?.isChecked   = currentFilter == ResultsFilter.PMFI
        filterCalibsItem?.isChecked = currentFilter == ResultsFilter.CALIBRATIONS

        super.onPrepareOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        // selection actions...
        R.id.action_select_all       -> { selectAllVisible(); true }
        R.id.action_share_selected   -> { shareSelected(); true }
        R.id.action_delete_selected  -> { deleteSelected(); true }
        R.id.action_cancel_selection -> { clearSelectionAndExitMode(); true }
        R.id.action_rename_inline    -> { promptRenameSelected(); true }

        // NEW filters
        R.id.filter_all -> {
            setFilter(ResultsFilter.ALL); item.isChecked = true; true
        }
        R.id.filter_amsi -> {
            setFilter(ResultsFilter.AMSI); item.isChecked = true; true
        }
        R.id.filter_pmfi -> {
            setFilter(ResultsFilter.PMFI); item.isChecked = true; true
        }
        R.id.filter_calibrations -> {
            setFilter(ResultsFilter.CALIBRATIONS); item.isChecked = true; true
        }

        else -> super.onOptionsItemSelected(item)
    }


    private fun setFilter(f: ResultsFilter) {
        if (currentFilter == f) return
        currentFilter = f
        val base = galleryViewModel.results.value ?: emptyList()
        submitMerged(applyFilterAndSort(base), forceHideInProgress = true)
        clearSelectionAndExitMode()
    }


    private fun invalidateSelectionUI() {
        requireActivity().invalidateOptionsMenu()
    }


    private fun Session.isAMSI(): Boolean = type.equals("AMSI", ignoreCase = true)
    private fun Session.isPMFI(): Boolean = type.equals("PMFI", ignoreCase = true)


    private fun sortKeyForItem(item: ResultListItem): Long {
        return when (item) {
            is ResultListItem.SessionItem ->
                stableMillisForSession(item.session)

            is ResultListItem.CalibrationItem ->
                stableMillisForCalibration(item.profile)

            is ResultListItem.InProgress ->
                Long.MAX_VALUE
        }
    }




    private fun stableIdForItem(item: ResultListItem): Long {
        return when (item) {
            is ResultListItem.SessionItem     -> item.session.id
            is ResultListItem.CalibrationItem -> stableCalibId(item.profile)
            is ResultListItem.InProgress      -> Long.MAX_VALUE - 1
        }
    }

    private fun stableMillisForSession(session: Session): Long {
        // Prefer completedAtMillis if present, else createdAt, else 0
        return when {
            session.completedAtMillis != null && session.completedAtMillis > 0L ->
                session.completedAtMillis
            session.createdAt > 0L ->
                session.createdAt
            else ->
                0L
        }
    }

    private fun stableMillisForCalibration(profile: CalibrationProfile): Long {
        // completedAtMillis is canonical for calibration runs
        return profile.completedAtMillis
    }


    // ---------- Selection ----------
    private fun idForSession(s: Session) = s.id
    private fun idForCalibration(p: CalibrationProfile) = stableCalibId(p)

    private fun isCalibrationId(id: Long): Boolean {
        return adapter.currentList.any { item ->
            item is ResultListItem.CalibrationItem &&
                    stableCalibId(item.profile) == id
        }
    }


    // Cached toolbar items
    private var menuItemRenameInline: MenuItem? = null
    private var filterAllItem: MenuItem? = null
    private var filterAmsiItem: MenuItem? = null
    private var filterPmfiItem: MenuItem? = null
    private var filterCalibsItem: MenuItem? = null

    private fun enterSelectionMode() {
        // caller updates selectedIds first; we just refresh toolbar state
        updateToolbarForSelection(selectedIds.size)
        backCallback.isEnabled = true
    }

    private fun clearSelectionAndExitMode() {
        inSelectionMode = false
        selectedIds.clear()
        adapter.clearSelection()
        updateToolbarForSelection(0)
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
            .map { item ->
                when (item) {
                    is ResultListItem.SessionItem     -> idForSession(item.session)
                    is ResultListItem.CalibrationItem -> idForCalibration(item.profile)
                    is ResultListItem.InProgress      -> error("Filtered above")
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
        val valid = list
            .filter { it !is ResultListItem.InProgress }
            .map { item ->
                when (item) {
                    is ResultListItem.SessionItem     -> idForSession(item.session)
                    is ResultListItem.CalibrationItem -> idForCalibration(item.profile)
                    is ResultListItem.InProgress      -> error("Filtered above")
                }
            }
            .toSet()
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

    private fun submitMerged(base: List<ResultListItem>, forceHideInProgress: Boolean = false) {
        if (forceHideInProgress) hideInProgressOnce = true

        val merged = mutableListOf<ResultListItem>()
        val inProg = if (!hideInProgressOnce) buildInProgressItemOrNull() else null
        if (inProg != null && currentFilter != ResultsFilter.CALIBRATIONS) {
            merged.add(inProg)
        }
        merged.addAll(base)

        adapter.submitList(merged) {
            // Called after diff completes
            showEmptyState(merged.isEmpty() || merged.all { it is ResultListItem.InProgress })
            reconcileSelectionWith(merged)
            updateToolbarForSelection(selectedIds.size)
        }
    }
    private fun updateChips() {
        chipAll.isChecked  = currentFilter == ResultsFilter.ALL
        chipAmsi.isChecked = currentFilter == ResultsFilter.AMSI
        chipPmfi.isChecked = currentFilter == ResultsFilter.PMFI
        chipCalib.isChecked= currentFilter == ResultsFilter.CALIBRATIONS
    }

    private fun showEmptyState(show: Boolean) {
        emptyState.visibility = if (show) View.VISIBLE else View.GONE
        recyclerView.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }



    private fun openSession(session: Session) {
        startActivity(SessionDetailActivity.newIntent(requireContext(), session.id))
    }


    private fun openCalibration(profile: CalibrationProfile) {
        val wlToNorm = extractCalibrationMap(profile)

        val whenStr = profile.timestampStr.ifBlank {
            runCatching {
                SimpleDateFormat("dd-MM-yyyy | HH:mm", Locale.getDefault())
                    .format(Date(profile.completedAtMillis))
            }.getOrElse { "—" }
        }

        val header = buildString {
            appendLine("Calibration Profile")
            appendLine("-------------------")
            appendLine("Run ID: ${profile.runId}")
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

        val maybeCal = getSingleSelectedCalibrationProfile()
        val isCal = (maybeCal != null)

        val (currentTitle, setter) = if (isCal) {
            val prof = maybeCal!!
            val existing = displayNameFor(prof)
            existing to { newName: String -> renameCalibration(prof.runId, newName) }
        } else {
            val selSessionId = selectedIds.first()
            val session = currentSessionById(selSessionId)
            val existing = displayNameFor(session)
            existing to { newName: String -> renameSession(selSessionId, newName) }
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
                    input.error = "Name cannot be empty"; return@setOnClickListener
                }
                if (name.length > 64) {
                    input.error = "Keep it under 64 characters"; return@setOnClickListener
                }
                setter(name)
                dlg.dismiss()
            }
        }
        dlg.show()
    }
    private fun kindPrefixFor(session: Session) = when (session.type.uppercase(Locale.ROOT)) {
        "PMFI" -> "[PMFI]"
        "AMSI" -> "[AMSI]"
        else   -> "[${session.type.uppercase(Locale.ROOT)}]"
    }
    private fun folderKindTagFor(session: Session): String {
        // What shows up at the start of the folder name
        return when (session.type.uppercase(Locale.ROOT)) {
            "PMFI" -> "PMFI"
            "AMSI" -> "AMSI"
            else   -> session.type.uppercase(Locale.ROOT)
        }
    }

    private fun pmfiInfoFor(session: Session): String {
        // same cleaning as pmfiInfoForFilename, but we don't sanitize to underscores
        // and we don't prepend "INI_" because this is for UI
        val iniPretty = session.iniName
            ?.trim()
            ?.replace(Regex("^[a-f0-9]{16,}_?"), "") // kill long hex prefix (with or without underscore)
            ?.removeSuffix(".ini")
            ?.removeSuffix("_ini")
            ?.takeIf { it.isNotBlank() }

        val secPretty = session.label
            ?.takeIf { it.isNotBlank() }

        return listOfNotNull(iniPretty, secPretty)
            .joinToString(" • ")
    }

    // Produces a FILENAME-SAFE PMFI slug for exports (ZIP folder names).
// - Removes long hash-like ini names
// - Converts spaces etc. to underscores via sanitizeName()
// - Uses "__" separators instead of " • "
// Result example:
//   "INI_leafscan_v4__Section_000__sec_test_sine_part_001"
    private fun pmfiInfoForFilename(session: Session): String {
        // 1. Clean iniName aggressively
        val rawIni = session.iniName?.trim().orEmpty()

        // Strip:
        //   - any leading 16+ hex chars (with or without underscore after)
        //   - trailing ".ini"
        //   - trailing "_ini"
        // Then sanitize for filesystem use
        val cleanedIni = rawIni
            .replace(Regex("^[a-f0-9]{16,}_?"), "") // kill leading giant hex + optional underscore
            .removeSuffix(".ini")
            .removeSuffix("_ini")
            .takeIf { it.isNotBlank() }
            ?.let { "INI_${sanitizeName(it)}" } // prefix "INI_..." if we still have something
            ?: ""  // if nothing left, just drop it

        // 2. Section/wavelength label
        val cleanedSection = session.label
            ?.takeIf { it.isNotBlank() }
            ?.let { sanitizeName(it) } // e.g. "Section_000__sec_test_sine_part_001"
            ?: ""

        // 3. Stitch pieces with "__", skipping blanks neatly
        return listOf(cleanedIni, cleanedSection)
            .filter { it.isNotBlank() }
            .joinToString("__")
    }



    private fun kindPrefixFor(cal: CalibrationProfile) = "[CALIBRATION]"
    private fun renameSession(sessionId: Long, newName: String) {
        prefs.edit().putString(spKeySession(sessionId), newName).apply()
        refreshDisplayNameForId(sessionId)
        msg("Session renamed.")
    }

    private fun renameCalibration(runId: String, newName: String) {
        prefs.edit().putString(spKeyCalib(runId), newName).apply()

        // find matching adapter row and notify it so title refreshes
        val index = adapter.currentList.indexOfFirst { item ->
            item is ResultListItem.CalibrationItem &&
                    item.profile.runId == runId
        }
        if (index >= 0) {
            adapter.notifyItemChanged(index, ResultsAdapter.PAYLOAD_TITLE_CHANGED)
        }

        msg("Calibration renamed.")
    }


    // ---------- Share / Delete ----------
    private fun shareSelected() {
        val current = adapter.currentList
        val chosen = selectedIds.toSet()

        val sessions = mutableListOf<Session>()
        val calibs   = mutableListOf<CalibrationProfile>()

        // Figure out what was selected
        current.forEach { item ->
            when (item) {
                is ResultListItem.SessionItem -> {
                    if (chosen.contains(item.session.id)) {
                        sessions.add(item.session)
                    }
                }
                is ResultListItem.CalibrationItem -> {
                    if (chosen.contains(stableCalibId(item.profile))) {
                        calibs.add(item.profile)
                    }
                }
                is ResultListItem.InProgress -> Unit
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uris = arrayListOf<Uri>()

                // 1. Only include a sessions ZIP if at least one session was selected
                if (sessions.isNotEmpty()) {
                    buildSessionsZip(sessions)?.let { sessionsZip ->
                        // sanity check it actually has bytes on disk
                        if (sessionsZip.exists() && sessionsZip.length() > 0L) {
                            uris.add(fileUri(sessionsZip))
                        }
                    }
                }

                // 2. For each calibration: add calibration ZIP + calibration JSON
                calibs.forEach { calProfile ->
                    buildCalibrationZip(calProfile)?.let { calZip ->
                        if (calZip.exists() && calZip.length() > 0L) {
                            uris.add(fileUri(calZip))
                        }
                    }

                    makeCalibrationJsonFile(calProfile)?.let { jsonFile ->
                        if (jsonFile.exists() && jsonFile.length() > 0L) {
                            uris.add(fileUri(jsonFile))
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (uris.isEmpty()) {
                        msg("Nothing to share. Select at least one complete session or calibration.")
                        return@withContext
                    }

                    val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    // Grant URI read permissions to all possible targets
                    val targets = requireContext().packageManager.queryIntentActivities(share, 0)
                    targets.forEach { ri ->
                        val pkg = ri.activityInfo.packageName
                        uris.forEach { uri ->
                            requireContext().grantUriPermission(
                                pkg,
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    }

                    startActivity(Intent.createChooser(share, "Share results"))
                    clearSelectionAndExitMode()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    msg("Share failed: ${e.message}")
                }
            }
        }
    }


    private fun deleteSelected() {
        val current = adapter.currentList
        val chosen = selectedIds.toSet()

        val sessions = mutableListOf<Session>()
        val calibs = mutableListOf<CalibrationProfile>()

        current.forEach { item ->
            when (item) {
                is ResultListItem.SessionItem ->
                    if (chosen.contains(item.session.id))
                        sessions.add(item.session)

                is ResultListItem.CalibrationItem ->
                    if (chosen.contains(stableCalibId(item.profile)))
                        calibs.add(item.profile)
                is ResultListItem.InProgress -> Unit
            }
        }


        if (sessions.isEmpty() && calibs.isEmpty()) { msg("No items selected."); return }

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
                    val db = AppDatabase.getDatabase(requireContext())
                    val sessionDao = db.sessionDao()
                    val calibDao   = db.calibrationDao()

                    // Sessions: delete files + DB
                    sessions.forEach { s ->
                        s.imagePaths.forEach { path -> runCatching { File(path).delete() } }
                        runCatching { s.imagePaths.firstOrNull()?.let { File(it).parentFile?.deleteRecursively() } }
                        prefs.edit().remove(spKeySession(s.id)).apply()
                        runCatching { sessionDao.delete(s) }
                    }

                    // Calibrations: DB + prefs
                    calibs.forEach { c ->
                        prefs.edit().remove(spKeyCalib(c.runId)).apply()
                        runCatching { calibDao.deleteByRunId(c.runId) }
                    }


                    withContext(Dispatchers.Main) {
                        clearSelectionAndExitMode()
                        // Room LiveData will refresh automatically
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- ZIP / EXIF ----------




    private fun makeCalibrationJsonFile(profile: CalibrationProfile): File? = runCatching {
        val dir = File(requireContext().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "calibration_${profile.runId}.json")

        val wlToNorm = extractCalibrationMap(profile)

        val root = JSONObject().apply {
            put("runId", profile.runId)
            put("summary", profile.summary ?: "")
            put("completedAtMillis", profile.completedAtMillis)
            put("timestampStr", profile.timestampStr)

            // norms object: wavelength -> value
            val normsObj = JSONObject()
            wlToNorm.toSortedMap().forEach { (wl, v) ->
                normsObj.put(wl.toString(), v)
            }
            put("norms", normsObj)

            // raw LED norms list if you want to keep it
            // ledNormsJson is already a JSON array string, so parse+re-put
            val arr = JSONArray(profile.ledNormsJson ?: "[]")
            put("led_norms", arr)
        }

        FileOutputStream(file).use { it.write(root.toString(2).toByteArray()) }
        file
    }.getOrNull()



    private fun wavelengthForIndex(indexInSession: Int): Int? =
        AMSI_WAVELENGTHS.getOrNull(indexInSession)

    private fun parseSessionDate(sessionTimestamp: String): java.util.Date {
        // 1) Try ISO-8601 first (safest if you ever switch to it)
        runCatching {
            val instant = java.time.Instant.parse(sessionTimestamp.trim())
            return java.util.Date.from(instant)
        }

        // 2) Try known legacy patterns
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "dd/MM/yyyy HH:mm:ss",
            "EEE MMM dd HH:mm:ss zzz yyyy"
        )

        for (p in patterns) {
            val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("Europe/London")
            }
            val d = runCatching { fmt.parse(sessionTimestamp) }.getOrNull()
            if (d != null) {
                // sanity check year
                val cal = java.util.Calendar.getInstance().apply { time = d }
                val y = cal.get(java.util.Calendar.YEAR)
                if (y in 2000..2100) return d
            }
        }

        // 3) As a last resort, now()
        return java.util.Date()
    }


    private fun datePartsForName(session: Session): Pair<String, String> {
        fun fromDate(ms: Long): Pair<String, String> {
            val z = java.util.TimeZone.getTimeZone("Europe/London")
            val dFmt = java.text.SimpleDateFormat("dd_MM_yyyy", java.util.Locale.US).apply { timeZone = z }
            val tFmt = java.text.SimpleDateFormat("HH_mm_ss", java.util.Locale.US).apply { timeZone = z }
            val dt = java.util.Date(ms)
            return dFmt.format(dt) to tFmt.format(dt)
        }

        // Try parse
        val parsed = runCatching { parseSessionDate(session.timestamp) }.getOrNull()
        if (parsed != null) {
            val cal = java.util.Calendar.getInstance().apply { time = parsed }
            val y = cal.get(java.util.Calendar.YEAR)
            if (y in 2000..2100) return fromDate(parsed.time)
        }

        // Fallback: newest file in the session
        val newestMs = session.imagePaths
            .mapNotNull { runCatching { java.io.File(it).lastModified() }.getOrNull() }
            .filter { it > 0L }
            .maxOrNull()

        if (newestMs != null) return fromDate(newestMs)

        // Last resort: current time
        return fromDate(System.currentTimeMillis())
    }


    private fun buildFolderName(session: Session): String {
        val (d, t) = datePartsForName(session)

        val loc = session.location.trim().ifEmpty { "UnknownLoc" }

        val custom = prefs.getString(spKeySession(session.id), "")?.trim().orEmpty()
        val baseTitle = if (custom.isNotEmpty()) {
            sanitizeName(custom)
        } else {
            sanitizeName(defaultSessionTitle(session))
        }

        val envSlug = run {
            val temp = session.envTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "NA"
            val rh   = session.envHumidity?.let { String.format(Locale.US, "%.0f", it) } ?: "NA"
            "Env_T_${temp}C__RH_${rh}pc"
        }

        val kindTag = folderKindTagFor(session) // "PMFI", "AMSI", ...

        // >>> THIS IS THE IMPORTANT CHANGE <<<
        // For PMFI, we build a filename-safe LED/INI section slug with hash stripped.
        val pmfiSlugForFile = if (session.isPMFI()) {
            pmfiInfoForFilename(session).takeIf { it.isNotBlank() }
        } else null

        // Final shape in the ZIP:
        // [PMFI]_Session_149__Date_28_10_2025__Time_14_59_56__Loc_Stretford__Greater_Manchester...__INI_leafscan_v4__Section_000__sec_test_sine_part_001__Env_T_24.6C__RH_71pc
        // (No stupid giant hash anymore.)
        return buildString {
            append("[").append(kindTag).append("]_")
            append(baseTitle)
            append("__Date_").append(d)
            append("__Time_").append(t)
            append("__Loc_").append(sanitizeName(loc))

            if (pmfiSlugForFile != null) {
                append("__").append(pmfiSlugForFile)
            }

            append("__").append(envSlug)
        }
    }




    private fun buildSessionsZip(sessions: List<Session>): File? = runCatching {
        val cacheDir = File(requireContext().cacheDir, "shares").apply { mkdirs() }
        val zipFile = File(cacheDir, "sessions_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sessions.forEach { s ->
                val folder = buildFolderName(s)

                // natural-ish ordering by filename so PMFI sequences are stable
                val paths = s.imagePaths
                    .map { File(it) }
                    .filter { it.exists() }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }

                val isStandardAmsi = s.type.equals("AMSI", true) && paths.size == 16

                paths.forEachIndexed { idx, src ->
                    val outName = if (isStandardAmsi) {
                        // 16 fixed wavelengths
                        buildAmsiImageNameForShare(idx)
                    } else {
                        // PMFI / arbitrary count
                        "frame_${String.format(Locale.US, "%04d", idx + 1)}.png"
                    }

                    // copy + (light) EXIF tagging into a temp file
                    val tempCopy = File(cacheDir, "tmp_${System.nanoTime()}_$outName")
                    stampExifToCopy(src, tempCopy, s, idx, isStandardAmsi)

                    FileInputStream(tempCopy).use { fis ->
                        zos.putNextEntry(ZipEntry("$folder/$outName"))
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                    tempCopy.delete()
                }
            }
        }
        zipFile
    }.getOrNull()

    private fun buildAmsiImageNameForShare(index: Int): String {
        val wl = wavelengthForIndex(index)
        return if (wl != null) "Wavelength_${wl}nm.png" else "Wavelength_unknown.png"
    }

    private fun stampExifToCopy(
        src: File,
        dst: File,
        session: Session,
        indexInSession: Int,
        isStandardAmsi: Boolean
    ) {
        // just byte-copy first
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
        // Try to enrich EXIF (PNG may have limited support; we best-effort)
        runCatching {
            val exif = ExifInterface(dst.absolutePath)
            val dt = parseSessionDate(session.timestamp)
            val exifFormat = SimpleDateFormat("dd-MM-yyyy | HH:mm:ss", Locale.US)
            val dateStr = exifFormat.format(dt)

            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)

            val sb = StringBuilder().apply {
                append("MSI capture; Session ${session.id}; ")
                append("${session.timestamp}; ")
                append("Location: ${session.location.ifEmpty { "Unknown" }}")
                if (isStandardAmsi) {
                    wavelengthForIndex(indexInSession)?.let { wl ->
                        append("; Wavelength: ${wl}nm")
                    }
                } else {
                    append("; FrameIndex: ${indexInSession}")
                }
                append("; Type: ${session.type}")
            }

            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, sb.toString())
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, sb.toString())
            exif.saveAttributes()
        }
    }


    private fun displayNameFor(session: Session?): String {
        if (session == null) return "Session ${System.currentTimeMillis()}"

        // User rename
        val custom = prefs.getString(spKeySession(session.id), "")?.trim().orEmpty()
        val fallbackBaseTitle = "Session ${session.id}"

        // For PMFI we also have rich label info like "Section 000 (sec.test_sine:part_001)"
        val pmfiBits = pmfiInfoFor(session).trim()

        return when {
            // PMFI: show just the interesting PMFI label if we have it.
            session.isPMFI() -> {
                when {
                    pmfiBits.isNotEmpty() -> pmfiBits
                    custom.isNotEmpty()   -> custom
                    else                  -> fallbackBaseTitle
                }
            }

            // AMSI (and anything else that's essentially "normal capture"):
            session.isAMSI() -> {
                when {
                    custom.isNotEmpty()   -> custom
                    else                  -> fallbackBaseTitle
                }
            }

            // Any other type
            else -> {
                when {
                    custom.isNotEmpty()   -> custom
                    else                  -> fallbackBaseTitle
                }
            }
        }
    }

    private fun displayNameFor(profile: CalibrationProfile): String {
        val num = profile.id ?: stableCalibId(profile)
        return "Calibration $num"
    }



    private fun getSingleSelectedCalibrationProfile(): CalibrationProfile? {
        if (selectedIds.size != 1) return null
        val selStableId = selectedIds.first()

        val match = adapter.currentList.firstOrNull { item ->
            item is ResultListItem.CalibrationItem &&
                    stableCalibId(item.profile) == selStableId
        } as? ResultListItem.CalibrationItem

        return match?.profile
    }



    private fun subtitleFor(session: Session): String {
        // 1. Pick a base timestamp
        val baseMs = session.completedAtMillis ?: session.createdAt
        val tz = java.util.TimeZone.getTimeZone("Europe/London")

        val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
            timeZone = tz
        }
        val dateFmt = java.text.SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).apply {
            timeZone = tz
        }

        val dt      = java.util.Date(baseMs)
        val timeStr = timeFmt.format(dt)        // "14:59:56"
        val dateStr = dateFmt.format(dt)        // "28-10-2025"

        val locStr = session.location.trim().ifEmpty { "Unknown" }

        val tempStr = session.envTempC?.let {
            "T ${String.format(Locale.US, "%.1f", it)}°C"
        }
        val rhStr = session.envHumidity?.let {
            "RH ${String.format(Locale.US, "%.0f", it)}%"
        }

        // Core info applies to EVERY type now
        // For PMFI we DO NOT prepend pmfiBits anymore, because it's already in the title.
        return listOfNotNull(timeStr, dateStr, locStr, tempStr, rhStr)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }





    private fun subtitleFor(profile: CalibrationProfile): String {
        // 1. Try to get a reliable timestamp
        val millisCandidate: Long? = when {
            profile.completedAtMillis != 0L -> profile.completedAtMillis
            !profile.timestampStr.isNullOrBlank() -> {
                val raw = profile.timestampStr.trim()
                val fmts = listOf(
                    java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()),
                    java.text.SimpleDateFormat("dd-MM-yyyy | HH:mm", Locale.getDefault())
                )
                fmts.firstNotNullOfOrNull { fmt ->
                    runCatching { fmt.parse(raw)?.time }.getOrNull()
                }
            }
            else -> null
        }

        // 2. Format date + time
        val tz = java.util.TimeZone.getTimeZone("Europe/London")
        val dateStr = millisCandidate?.let {
            java.text.SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).apply {
                timeZone = tz
            }.format(java.util.Date(it))
        }
        val timeStr = millisCandidate?.let {
            java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                timeZone = tz
            }.format(java.util.Date(it))
        }

        // 3. Environment info (keep only temp & RH)
        val tempStr = profile.envTempC?.let {
            "T ${String.format(Locale.US, "%.1f", it)}°C"
        }
        val rhStr = profile.envHumidity?.let {
            "RH ${String.format(Locale.US, "%.0f", it)}%"
        }

        // 4. Join available fields
        return listOfNotNull(timeStr, dateStr, tempStr, rhStr)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }



    private fun buildCalibrationZip(profile: CalibrationProfile): File? = runCatching {
        val cacheDir = File(requireContext().cacheDir, "shares").apply { mkdirs() }

        val safeTitle = sanitizeName(displayNameFor(profile))

        val zipFile = File(
            cacheDir,
            "calibration_${safeTitle}_${System.currentTimeMillis()}.zip"
        )

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // NEW: folder name inside ZIP
            // Example:
            // [CALIBRATION]_Calibration_12345__Run_cal_20251028_1420
            val folderName = buildString {
                append("[CALIBRATION]_")
                append(safeTitle)
                append("__Run_")
                append(sanitizeName(profile.runId))
            }

            // Parse image paths list from profile.imagePathsJson
            val imagePaths = try {
                val arr = org.json.JSONArray(profile.imagePathsJson ?: "[]")
                List(arr.length()) { i -> arr.getString(i) }
            } catch (e: Exception) {
                emptyList<String>()
            }

            imagePaths
                .map { path -> File(path) }
                .filter { it.exists() }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .forEachIndexed { idx, src ->
                    val prettyName = buildCalibrationImageNameForShare(profile, idx, src)

                    FileInputStream(src).use { fis ->
                        zos.putNextEntry(ZipEntry("$folderName/$prettyName"))
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                }
        }

        zipFile
    }.getOrNull()


    // Optionally smarter names if you stored wavelength/channel mapping in the profile
    private fun buildCalibrationImageNameForShare(
        profile: CalibrationProfile,
        index: Int,
        fileOnDisk: File
    ): String {
        // Try to include wavelength if we have it, else fall back to CAL_image_xx.png
        // You can extend CalibrationProfile to hold per-channel wavelength info.
        return "cal_channel_${String.format(Locale.US, "%02d", index)}.png"
    }



    private fun defaultSessionTitle(session: Session): String = "Session ${session.id}"

    private fun sanitizeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").replace("\\s+".toRegex(), "_")

    private fun extractCalibrationMap(profile: CalibrationProfile): Map<Int, Double> {
        val list = parseLedNormsJson(profile.ledNormsJson)
        return if (list.size == AMSI_WAVELENGTHS.size) {
            AMSI_WAVELENGTHS.indices.associate { i -> AMSI_WAVELENGTHS[i] to list[i] }
        } else emptyMap()
    }

    private fun parseLedNormsJson(json: String?): List<Double> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { idx -> arr.optDouble(idx) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun refreshDisplayNameForId(selId: Long) {
        // sessions (unchanged)
        val sessionIndex = adapter.currentList.indexOfFirst { item ->
            item is ResultListItem.SessionItem && item.session.id == selId
        }
        if (sessionIndex >= 0) {
            adapter.notifyItemChanged(sessionIndex, ResultsAdapter.PAYLOAD_TITLE_CHANGED)
            return
        }

        // calibrations
        val calibIndex = adapter.currentList.indexOfFirst { item ->
            item is ResultListItem.CalibrationItem &&
                    stableCalibId(item.profile) == selId
        }
        if (calibIndex >= 0) {
            adapter.notifyItemChanged(calibIndex, ResultsAdapter.PAYLOAD_TITLE_CHANGED)
        }
    }


    private fun currentSessionById(id: Long): Session? =
        adapter.currentList.firstOrNull {
            it is ResultListItem.SessionItem && it.session.id == id
        }?.let { (it as ResultListItem.SessionItem).session }


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
    fun subtitleFor(cal: CalibrationProfile): String
}
