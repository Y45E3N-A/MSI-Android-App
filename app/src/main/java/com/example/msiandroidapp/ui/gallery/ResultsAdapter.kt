package com.example.msiandroidapp.ui.gallery

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session

class ResultsAdapter(
    private val onSessionClick: (Session) -> Unit,
    private val onSessionLongClick: (Session) -> Unit,
    private val onCalibrationClick: (CalibrationProfile) -> Unit,
    private val onCalibrationLongClick: (CalibrationProfile) -> Unit,
    private val displayNameProvider: DisplayNameProvider
) : ListAdapter<ResultListItem, RecyclerView.ViewHolder>(DiffCallback) {

    init { setHasStableIds(true) }

    // --- Selection owned by Fragment; adapter renders it ---
    private var inSelectionMode: Boolean = false
    private var selectedIds: Set<Long> = emptySet()

    /** Optional callback if the Fragment wants to react when selection becomes empty/non-empty. */
    var onSelectionChanged: ((Boolean, Int) -> Unit)? = null

    fun setSelectionMode(enabled: Boolean) {
        if (inSelectionMode == enabled) return
        inSelectionMode = enabled
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun updateSelection(newSelection: Set<Long>) {
        val old = selectedIds
        val fresh = newSelection.toSet()
        selectedIds = fresh

        // Rebind rows whose selection state changed
        if (old != fresh) {
            for (pos in 0 until itemCount) {
                val id = getItemId(pos)
                val was = id in old
                val now = id in fresh
                if (was != now) notifyItemChanged(pos, PAYLOAD_SELECTION)
            }
        }

        // 🔧 Auto-toggle selection mode based on whether anything is selected.
        val shouldBeInSelectionMode = fresh.isNotEmpty()
        if (inSelectionMode != shouldBeInSelectionMode) {
            inSelectionMode = shouldBeInSelectionMode
            // Rebind all to show/hide the checkbox icons.
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        }

        onSelectionChanged?.invoke(fresh.isNotEmpty(), fresh.size)
    }


    fun getSelection(): Set<Long> = selectedIds
    fun isInSelectionMode(): Boolean = inSelectionMode
    fun hasSelection(): Boolean = selectedIds.isNotEmpty()

    /** Lets Back press clear checks without closing the screen. */
    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds = emptySet()
        inSelectionMode = false
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(false, 0)
    }

    companion object {
        private const val TYPE_IN_PROGRESS = 0
        private const val TYPE_SESSION = 1
        private const val TYPE_CALIB = 2

        const val PAYLOAD_TITLE_CHANGED = "title_changed"
        private const val PAYLOAD_SELECTION = "selection_changed"

        val DiffCallback = object : DiffUtil.ItemCallback<ResultListItem>() {
            override fun areItemsTheSame(old: ResultListItem, new: ResultListItem): Boolean =
                when {
                    old is ResultListItem.InProgress && new is ResultListItem.InProgress -> true
                    old is ResultListItem.SessionItem && new is ResultListItem.SessionItem ->
                        old.session.id == new.session.id
                    old is ResultListItem.CalibrationItem && new is ResultListItem.CalibrationItem ->
                        old.profile.id == new.profile.id
                    else -> false
                }

            override fun areContentsTheSame(old: ResultListItem, new: ResultListItem): Boolean = old == new
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ResultListItem.InProgress -> TYPE_IN_PROGRESS
        is ResultListItem.SessionItem -> TYPE_SESSION
        is ResultListItem.CalibrationItem -> TYPE_CALIB
    }

    override fun getItemId(position: Int): Long = when (val item = getItem(position)) {
        is ResultListItem.InProgress -> Long.MIN_VALUE
        is ResultListItem.SessionItem -> item.session.id
        is ResultListItem.CalibrationItem -> 1_000_000_000_000L + item.profile.id // separate ID space
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_IN_PROGRESS -> InProgressVH(inf.inflate(R.layout.item_in_progress_session, parent, false))
            TYPE_SESSION -> SessionVH(inf.inflate(R.layout.item_completed_result, parent, false))
            TYPE_CALIB -> CalibrationVH(inf.inflate(R.layout.item_completed_result, parent, false))
            else -> error("Unknown viewType $viewType")
        }
    }

    // Lightweight rebinds for rename/selection
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            when {
                payloads.contains(PAYLOAD_TITLE_CHANGED) -> {
                    when (holder) {
                        is SessionVH -> holder.bindTitleOnly(getItem(position) as ResultListItem.SessionItem)
                        is CalibrationVH -> holder.bindTitleOnly(getItem(position) as ResultListItem.CalibrationItem)
                    }
                    return
                }
                payloads.contains(PAYLOAD_SELECTION) -> {
                    val selected = selectedIds.contains(getItemId(position))
                    when (holder) {
                        is SessionVH -> holder.applySelectionVisuals(selected, inSelectionMode)
                        is CalibrationVH -> holder.applySelectionVisuals(selected, inSelectionMode)
                    }
                    return
                }
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ResultListItem.InProgress -> (holder as InProgressVH).bind(item)

            is ResultListItem.SessionItem -> (holder as SessionVH).bind(
                item,
                onClick = onSessionClick,
                onLong = onSessionLongClick,
                selected = selectedIds.contains(getItemId(position)),
                inSelection = inSelectionMode
            )

            is ResultListItem.CalibrationItem -> (holder as CalibrationVH).bind(
                item,
                onClick = onCalibrationClick,
                onLong = onCalibrationLongClick,
                selected = selectedIds.contains(getItemId(position)),
                inSelection = inSelectionMode
            )
        }
    }

    // ======== ViewHolders ========

    class InProgressVH(view: View) : RecyclerView.ViewHolder(view) {
        private val progressText: TextView = view.findViewById(R.id.progress_text)
        private val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        private val grid: GridLayout = view.findViewById(R.id.in_progress_grid)

        fun bind(item: ResultListItem.InProgress) {
            progressText.text = "Receiving images: ${item.imageCount}/16"
            progressBar.max = 16
            progressBar.progress = item.imageCount

            // Simple grid preview (placeholder + any bitmaps provided)
            grid.removeAllViews()
            for (i in 0 until 16) {
                val iv = ImageView(itemView.context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 160
                        height = 160
                        setMargins(4, 4, 4, 4)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    item.bitmaps.getOrNull(i)?.let { setImageBitmap(it) }
                }
                grid.addView(iv)
            }
        }
    }
    inner class SessionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.completed_title)
        private val subtitle: TextView = view.findViewById(R.id.completed_subtitle)
        private val badge: TextView = view.findViewById(R.id.completed_badge)
        private val selectionIcon: ImageView? = view.findViewById(R.id.selection_icon)

        fun bind(
            item: ResultListItem.SessionItem,
            onClick: (Session) -> Unit,
            onLong: (Session) -> Unit,
            selected: Boolean,
            inSelection: Boolean
        ) {
            val session = item.session
            title.text = displayNameProvider.titleFor(session)
            subtitle.text = displayNameProvider.subtitleFor(session)

            // --- New: badge style + color based on type ---
            val type = session.type.uppercase()
            when (type) {
                "PMFI" -> {
                    badge.text = "PMFI"
                    badge.setBackgroundResource(R.drawable.bg_badge_pmfi)
                }
                "AMSI" -> {
                    badge.text = "AMSI"
                    badge.setBackgroundResource(R.drawable.bg_badge_amsi)
                }
                else -> {
                    badge.text = type
                    badge.setBackgroundResource(R.drawable.bg_badge_default)
                }
            }

            itemView.setOnClickListener { onClick(session) }
            itemView.setOnLongClickListener { onLong(session); true }

            applySelectionVisuals(selected, inSelection)
            selectionIcon?.setOnClickListener { if (inSelection) onClick(session) }
        }

        fun bindTitleOnly(item: ResultListItem.SessionItem) {
            title.text = displayNameProvider.titleFor(item.session)
        }

        fun applySelectionVisuals(selected: Boolean, inSelection: Boolean) {
            selectionIcon?.apply {
                isVisible = inSelection
                setImageResource(
                    if (selected) R.drawable.ic_check_box_24
                    else R.drawable.ic_check_box_outline_blank_24
                )
                isEnabled = inSelection
            }
            itemView.setBackgroundColor(
                if (selected) Color.parseColor("#203296FB") else Color.TRANSPARENT
            )
        }
    }

    inner class CalibrationVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.completed_title)
        private val subtitle: TextView = view.findViewById(R.id.completed_subtitle)
        private val badge: TextView = view.findViewById(R.id.completed_badge)
        private val selectionIcon: ImageView? = view.findViewById(R.id.selection_icon)

        fun bind(
            item: ResultListItem.CalibrationItem,
            onClick: (CalibrationProfile) -> Unit,
            onLong: (CalibrationProfile) -> Unit,
            selected: Boolean,
            inSelection: Boolean
        ) {
            val cal = item.profile
            title.text = displayNameProvider.titleFor(cal)
            subtitle.text = cal.summary ?: "Calibration saved"

            // --- New: consistent orange calibration badge ---
            badge.text = "CALIBRATION"
            badge.setBackgroundResource(R.drawable.bg_badge_calib)

            itemView.setOnClickListener { onClick(cal) }
            itemView.setOnLongClickListener { onLong(cal); true }

            applySelectionVisuals(selected, inSelection)
            selectionIcon?.setOnClickListener { if (inSelection) onClick(cal) }
        }

        fun bindTitleOnly(item: ResultListItem.CalibrationItem) {
            title.text = displayNameProvider.titleFor(item.profile)
        }

        fun applySelectionVisuals(selected: Boolean, inSelection: Boolean) {
            selectionIcon?.apply {
                isVisible = inSelection
                setImageResource(
                    if (selected) R.drawable.ic_check_box_24
                    else R.drawable.ic_check_box_outline_blank_24
                )
                isEnabled = inSelection
            }
            itemView.setBackgroundColor(
                if (selected) Color.parseColor("#203296FB") else Color.TRANSPARENT
            )
        }
    }




}
