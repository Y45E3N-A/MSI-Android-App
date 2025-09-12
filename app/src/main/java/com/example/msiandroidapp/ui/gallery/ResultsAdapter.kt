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
) : ListAdapter<ResultListItem, RecyclerView.ViewHolder>(DiffCallback) {

    init { setHasStableIds(true) }

    private var inSelectionMode = false
    private var selectedIds: Set<Long> = emptySet()

    fun setSelectionMode(enabled: Boolean) {
        if (inSelectionMode == enabled) return
        inSelectionMode = enabled
        notifyDataSetChanged()
    }

    fun updateSelection(newSelection: Set<Long>) {
        val old = selectedIds
        val fresh = newSelection.toSet()
        val changed = (old union fresh) - (old intersect fresh)
        selectedIds = fresh
        if (changed.isEmpty()) return

        // Rebind only changed rows (both sessions & calibrations)
        for (i in 0 until itemCount) {
            val id = getItemId(i)
            if (changed.contains(id)) notifyItemChanged(i, PAYLOAD_SELECTION)
        }
    }

    fun getSelection(): Set<Long> = selectedIds
    fun isInSelectionMode(): Boolean = inSelectionMode

    companion object {
        private const val TYPE_IN_PROGRESS = 0
        private const val TYPE_SESSION     = 1
        private const val TYPE_CALIB       = 2
        private const val PAYLOAD_SELECTION = "selection_changed"

        val DiffCallback = object : DiffUtil.ItemCallback<ResultListItem>() {
            override fun areItemsTheSame(old: ResultListItem, new: ResultListItem): Boolean =
                when {
                    old is ResultListItem.InProgress      && new is ResultListItem.InProgress      -> true
                    old is ResultListItem.SessionItem     && new is ResultListItem.SessionItem     ->
                        old.session.id == new.session.id
                    old is ResultListItem.CalibrationItem && new is ResultListItem.CalibrationItem ->
                        old.profile.id == new.profile.id
                    else -> false
                }

            override fun areContentsTheSame(old: ResultListItem, new: ResultListItem): Boolean = old == new
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ResultListItem.InProgress      -> TYPE_IN_PROGRESS
        is ResultListItem.SessionItem     -> TYPE_SESSION
        is ResultListItem.CalibrationItem -> TYPE_CALIB
    }

    override fun getItemId(position: Int): Long = when (val item = getItem(position)) {
        is ResultListItem.InProgress      -> Long.MIN_VALUE
        is ResultListItem.SessionItem     -> item.session.id
        is ResultListItem.CalibrationItem -> 1_000_000_000_000L + item.profile.id  // keep distinct space
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_IN_PROGRESS -> InProgressVH(inf.inflate(R.layout.item_in_progress_session, parent, false))
            TYPE_SESSION,
            TYPE_CALIB       -> ResultVH(inf.inflate(R.layout.item_completed_result, parent, false))
            else -> error("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION) && holder is ResultVH) {
            val id = getItemId(position)
            val selected = selectedIds.contains(id)
            holder.applySelectionVisuals(selected, inSelectionMode)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ResultListItem.InProgress -> (holder as InProgressVH).bind(item)

            is ResultListItem.SessionItem -> (holder as ResultVH).bindSession(
                session     = item.session,
                onClick     = onSessionClick,
                onLong      = onSessionLongClick,
                selected    = selectedIds.contains(getItemId(position)),
                inSelection = inSelectionMode
            )

            is ResultListItem.CalibrationItem -> (holder as ResultVH).bindCalibration(
                profile     = item.profile,
                onClick     = onCalibrationClick,    // 🔹 now toggles selection in fragment
                onLong      = onCalibrationLongClick,
                selected    = selectedIds.contains(getItemId(position)),
                inSelection = inSelectionMode
            )
        }
    }

    class InProgressVH(view: View) : RecyclerView.ViewHolder(view) {
        private val progressText: TextView  = view.findViewById(R.id.progress_text)
        private val progressBar:  ProgressBar = view.findViewById(R.id.progress_bar)
        private val grid:         GridLayout  = view.findViewById(R.id.in_progress_grid)

        fun bind(item: ResultListItem.InProgress) {
            progressText.text = "Receiving images: ${item.imageCount}/16"
            progressBar.max = 16
            progressBar.progress = item.imageCount

            grid.removeAllViews()
            for (i in 0 until 16) {
                val iv = ImageView(itemView.context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 160; height = 160; setMargins(4, 4, 4, 4)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    item.bitmaps.getOrNull(i)?.let { setImageBitmap(it) }
                }
                grid.addView(iv)
            }
        }
    }

    class ResultVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title:         TextView   = view.findViewById(R.id.completed_title)
        private val subtitle:      TextView   = view.findViewById(R.id.completed_subtitle)
        private val badge:         TextView   = view.findViewById(R.id.completed_badge)
        private val selectionIcon: ImageView? = view.findViewById(R.id.selection_icon)

        fun bindSession(
            session: Session,
            onClick: (Session) -> Unit,
            onLong:  (Session) -> Unit,
            selected: Boolean,
            inSelection: Boolean
        ) {
            title.text = "Session: ${session.timestamp}"
            subtitle.text = session.location
            badge.text = "SESSION"

            itemView.setOnClickListener { onClick(session) }
            itemView.setOnLongClickListener { onLong(session); true }

            applySelectionVisuals(selected, inSelection)
            selectionIcon?.setOnClickListener { if (inSelection) onClick(session) }
        }

        // 🔹 NEW: calibration rows also adopt selection visuals when selection mode is on
        fun bindCalibration(
            profile: CalibrationProfile,
            onClick: (CalibrationProfile) -> Unit,
            onLong:  (CalibrationProfile) -> Unit,
            selected: Boolean,
            inSelection: Boolean
        ) {
            title.text = profile.name
            subtitle.text = profile.summary ?: "Calibration saved"
            badge.text = "CALIBRATION"

            itemView.setOnClickListener { onClick(profile) }
            itemView.setOnLongClickListener { onLong(profile); true }

            applySelectionVisuals(selected, inSelection)
            selectionIcon?.setOnClickListener { if (inSelection) onClick(profile) }
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
