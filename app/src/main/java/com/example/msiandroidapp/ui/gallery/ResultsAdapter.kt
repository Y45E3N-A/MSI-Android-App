package com.example.msiandroidapp.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.msiandroidapp.R
import com.example.msiandroidapp.data.CalibrationProfile
import com.example.msiandroidapp.data.Session
import java.io.File

class ResultsAdapter(
    val onSessionClick: (Session) -> Unit,
    val onSessionLongClick: (Session) -> Unit,
    val onCalibrationClick: (CalibrationProfile) -> Unit,
    val onCalibrationLongClick: (CalibrationProfile) -> Unit,
) : ListAdapter<ResultListItem, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val TYPE_IN_PROGRESS = 0
        private const val TYPE_SESSION     = 1
        private const val TYPE_CALIB       = 2

        val DiffCallback = object : DiffUtil.ItemCallback<ResultListItem>() {
            override fun areItemsTheSame(old: ResultListItem, new: ResultListItem): Boolean {
                return when {
                    old is ResultListItem.InProgress && new is ResultListItem.InProgress -> true
                    old is ResultListItem.SessionItem  && new is ResultListItem.SessionItem ->
                        old.session.id == new.session.id
                    old is ResultListItem.CalibrationItem && new is ResultListItem.CalibrationItem ->
                        old.profile.id == new.profile.id
                    else -> false
                }
            }
            override fun areContentsTheSame(old: ResultListItem, new: ResultListItem): Boolean = old == new
        }
    }


    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ResultListItem.InProgress     -> TYPE_IN_PROGRESS
        is ResultListItem.SessionItem    -> TYPE_SESSION
        is ResultListItem.CalibrationItem-> TYPE_CALIB
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_IN_PROGRESS -> InProgressVH(inf.inflate(R.layout.item_in_progress_session, parent, false))
            TYPE_SESSION     -> ResultVH(inf.inflate(R.layout.item_completed_result, parent, false))
            TYPE_CALIB       -> ResultVH(inf.inflate(R.layout.item_completed_result, parent, false))
            else -> error("Unknown viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ResultListItem.InProgress -> (holder as InProgressVH).bind(item)
            is ResultListItem.SessionItem -> (holder as ResultVH).bindSession(item.session, onSessionClick, onSessionLongClick)
            is ResultListItem.CalibrationItem -> (holder as ResultVH).bindCalibration(item.profile, onCalibrationClick, onCalibrationLongClick)
        }
    }

    // --- In-progress ViewHolder (unchanged layout) ---
    class InProgressVH(view: View) : RecyclerView.ViewHolder(view) {
        private val progressText: TextView = view.findViewById(R.id.progress_text)
        private val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        private val grid: GridLayout = view.findViewById(R.id.in_progress_grid)

        fun bind(item: ResultListItem.InProgress) {
            progressText.text = "Receiving images: ${item.imageCount}/16"
            progressBar.max = 16
            progressBar.progress = item.imageCount
            grid.removeAllViews()
            for (i in 0 until 16) {
                val imgView = ImageView(itemView.context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 160; height = 160; setMargins(4, 4, 4, 4)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    item.bitmaps.getOrNull(i)?.let { setImageBitmap(it) }
                }
                grid.addView(imgView)
            }
        }
    }

    // --- Shared row for Session/Calibration with a type badge ---
    class ResultVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.completed_title)
        private val subtitle: TextView = view.findViewById(R.id.completed_subtitle)
        private val badge: TextView = view.findViewById(R.id.completed_badge)

        fun bindSession(session: Session,
                        onClick: (Session) -> Unit,
                        onLong: (Session) -> Unit) {
            title.text = "Session: ${session.timestamp}"
            subtitle.text = session.location
            badge.text = "SESSION"
            itemView.setOnClickListener { onClick(session) }
            itemView.setOnLongClickListener { onLong(session); true }
        }

        fun bindCalibration(profile: CalibrationProfile,
                            onClick: (CalibrationProfile) -> Unit,
                            onLong: (CalibrationProfile) -> Unit) {
            title.text = profile.name
            subtitle.text = profile.summary ?: "Calibration saved"
            badge.text = "CALIBRATION"
            itemView.setOnClickListener { onClick(profile) }
            itemView.setOnLongClickListener { onLong(profile); true }
        }
    }
}
