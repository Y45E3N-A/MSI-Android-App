package com.example.msiandroidapp.ui.gallery

import android.graphics.Bitmap
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
import com.example.msiandroidapp.data.Session

class SessionAdapter(
    val onClick: (Session) -> Unit,
    val onLongClick: (Session) -> Unit
) : ListAdapter<SessionListItem, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val TYPE_IN_PROGRESS = 0
        private const val TYPE_COMPLETED = 1

        val DiffCallback = object : DiffUtil.ItemCallback<SessionListItem>() {
            override fun areItemsTheSame(old: SessionListItem, new: SessionListItem): Boolean {
                return when {
                    old is SessionListItem.InProgress && new is SessionListItem.InProgress -> true
                    old is SessionListItem.Completed && new is SessionListItem.Completed ->
                        old.session.id == new.session.id
                    else -> false
                }
            }

            override fun areContentsTheSame(old: SessionListItem, new: SessionListItem): Boolean {
                return old == new
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SessionListItem.InProgress -> TYPE_IN_PROGRESS
            is SessionListItem.Completed -> TYPE_COMPLETED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_IN_PROGRESS -> InProgressViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_in_progress_session, parent, false)
            )
            TYPE_COMPLETED -> CompletedViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_completed_session, parent, false)
            )
            else -> throw IllegalArgumentException()
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SessionListItem.InProgress -> (holder as InProgressViewHolder).bind(item)
            is SessionListItem.Completed -> (holder as CompletedViewHolder).bind(item.session, onClick, onLongClick)
        }
    }

    // --- In-progress ViewHolder ---
    class InProgressViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val progressText: TextView = view.findViewById(R.id.progress_text)
        private val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        private val grid: GridLayout = view.findViewById(R.id.in_progress_grid)

        fun bind(item: SessionListItem.InProgress) {
            progressText.text = "Receiving images: ${item.imageCount}/16"
            progressBar.max = 16
            progressBar.progress = item.imageCount
            // Fill the grid with previews
            grid.removeAllViews()
            for (i in 0 until 16) {
                val imgView = ImageView(itemView.context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 160
                        height = 160
                        setMargins(4, 4, 4, 4)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    item.bitmaps[i]?.let { setImageBitmap(it) }
                }
                grid.addView(imgView)
            }
        }
    }

    // --- Completed Session ViewHolder ---
    class CompletedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.completed_title)
        private val subtitle: TextView = view.findViewById(R.id.completed_subtitle)

        fun bind(session: Session, onClick: (Session) -> Unit, onLongClick: (Session) -> Unit) {
            title.text = "Session: ${session.timestamp}"
            subtitle.text = session.location
            itemView.setOnClickListener { onClick(session) }
            itemView.setOnLongClickListener { onLongClick(session); true }
        }
    }
}
