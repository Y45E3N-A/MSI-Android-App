package com.example.msiandroidapp.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.msiandroidapp.R
import com.example.msiandroidapp.util.HighBitDepthImageDecoder
import java.io.File

class ImagePagerAdapter(
    private val items: List<ImageItem>
) : RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {

    data class ImageItem(
        val uri: Uri,
        val title: String
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_pager, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.pager_image_view)
        private val titleView: TextView = itemView.findViewById(R.id.pager_image_title)

        fun bind(item: ImageItem) {
            imageView.setImageBitmap(decodeBitmap(item.uri))
            titleView.text = item.title
        }

        private fun decodeBitmap(uri: Uri): Bitmap? {
            val path = uri.path ?: return null
            val file = File(path)
            return HighBitDepthImageDecoder.decodeFile(file)
        }
    }
}
