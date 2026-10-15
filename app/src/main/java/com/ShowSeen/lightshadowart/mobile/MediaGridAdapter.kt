package com.ShowSeen.lightshadowart.mobile

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.ShowSeen.lightshadowart.mobile.net.MediaItem

/** Thumbnail loading, implemented by the page with the session HTTP client (it owns the cache). */
interface ThumbnailProvider {
    fun load(item: MediaItem, onResult: (Bitmap?) -> Unit)
}

/**
 * Gallery adapter: rounded square thumbnails with video / favourite / selection markers.
 *
 * A long press opens the page's context menu (UI PRD §3.6 F) instead of entering selection mode,
 * so "long press shows a menu" and "the Select button starts multi-select" never fight each other.
 */
class MediaGridAdapter(private val thumbnails: ThumbnailProvider) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private val selected = mutableSetOf<String>()

    /** Flattened rows: date headers are inserted first when [groupByDate] is on */
    private val rows = mutableListOf<Row>()

    /** Multi-select: a tap marks the item instead of starting playback */
    var selectionMode: Boolean = false
        private set

    /** Whether to group by date (date view): the header shows the yyyy-MM-dd group key */
    var groupByDate: Boolean = false
        private set

    var onItemClick: ((MediaItem) -> Unit)? = null
    var onItemLongClick: ((MediaItem) -> Unit)? = null

    /** Selection changed, so the page can enable / disable the delete button */
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun submit(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        selected.retainAll(items.map { it.path }.toSet())
        rebuildRows()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun setGroupByDate(enabled: Boolean) {
        if (groupByDate == enabled) return
        groupByDate = enabled
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun rebuildRows() {
        rows.clear()
        var lastDate: String? = null
        items.forEach { item ->
            if (groupByDate && item.date.isNotEmpty() && item.date != lastDate) {
                rows.add(Row.Header(item.date))
                lastDate = item.date
            }
            rows.add(Row.Media(item))
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun selectedPaths(): List<String> = selected.toList()

    /** Whether every selected item is already a favourite (drives the favourite button label). */
    fun selectedAllFavourite(): Boolean {
        val picked = items.filter { selected.contains(it.path) }
        return picked.isNotEmpty() && picked.all { it.favourite }
    }

    private fun toggleSelection(item: MediaItem) {
        if (!selected.add(item.path)) selected.remove(item.path)
        val index = rows.indexOfFirst { it is Row.Media && it.item.path == item.path }
        if (index >= 0) notifyItemChanged(index)
        onSelectionChanged?.invoke(selected.size)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_MEDIA

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
        } else {
            MediaViewHolder(inflater.inflate(R.layout.item_media, parent, false), thumbnails)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.date)
            is Row.Media -> {
                val item = row.item
                val mediaHolder = holder as MediaViewHolder
                mediaHolder.bind(item, selected.contains(item.path))
                mediaHolder.itemView.setOnClickListener {
                    if (selectionMode) toggleSelection(item) else onItemClick?.invoke(item)
                }
                mediaHolder.itemView.setOnLongClickListener {
                    onItemLongClick?.invoke(item)
                    true
                }
            }
        }
    }

    /** A display row: either a date header or a media entry */
    private sealed interface Row {
        data class Header(val date: String) : Row
        data class Media(val item: MediaItem) : Row
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tv_date_header)

        fun bind(date: String) {
            title.text = date
        }
    }

    class MediaViewHolder(
        itemView: View,
        private val thumbnails: ThumbnailProvider
    ) : RecyclerView.ViewHolder(itemView) {

        private val thumb: ImageView = itemView.findViewById(R.id.iv_thumb)
        private val videoBadge: TextView = itemView.findViewById(R.id.tv_video_badge)
        private val favourite: ImageView = itemView.findViewById(R.id.iv_favourite)
        private val check: ImageView = itemView.findViewById(R.id.iv_check)
        private val overlay: View = itemView.findViewById(R.id.view_select_overlay)

        fun bind(item: MediaItem, selected: Boolean) {
            videoBadge.isVisible = item.isVideo
            favourite.isVisible = item.favourite
            check.isVisible = selected
            overlay.isVisible = selected

            // Tag the bound path: a late thumbnail for a recycled holder must be dropped
            thumb.tag = item.path
            thumb.setImageDrawable(null)
            thumb.imageTintList = null
            thumbnails.load(item) { bitmap ->
                if (thumb.tag != item.path) return@load
                if (bitmap != null) {
                    thumb.setImageBitmap(bitmap)
                } else {
                    // Failed (or a video with no decoded frame): a clear placeholder, not a blank cell
                    thumb.setImageResource(R.drawable.ic_image_broken)
                    thumb.imageTintList = ContextCompat.getColorStateList(
                        itemView.context,
                        R.color.text_disabled
                    )
                }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
    }
}
