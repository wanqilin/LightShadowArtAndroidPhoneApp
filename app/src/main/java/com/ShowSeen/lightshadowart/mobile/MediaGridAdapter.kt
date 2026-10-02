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

/** 缩略图加载：由 Activity 用会话内的 HTTP 客户端实现（自带内存缓存），回调保证在主线程 */
interface ThumbnailProvider {
    fun load(item: MediaItem, onResult: (Bitmap?) -> Unit)
}

/**
 * 空间媒体网格：每行若干列，展示缩略图并可多选删除、标记喜爱（需求 6.1）。
 */
class MediaGridAdapter(private val thumbnails: ThumbnailProvider) :
    RecyclerView.Adapter<MediaGridAdapter.MediaViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private val selected = mutableSetOf<String>()

    /** 多选模式：开启后点击为勾选，关闭后点击为切播放 */
    var selectionMode: Boolean = false
        private set

    var onItemClick: ((MediaItem) -> Unit)? = null
    var onItemLongClick: ((MediaItem) -> Unit)? = null

    /** 选择集变化时回调，供界面刷新「删除」按钮可用态 */
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun submit(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        selected.retainAll(items.map { it.path }.toSet())
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun selectedPaths(): List<String> = selected.toList()

    private fun toggleSelection(item: MediaItem) {
        if (!selected.add(item.path)) selected.remove(item.path)
        val index = items.indexOfFirst { it.path == item.path }
        if (index >= 0) notifyItemChanged(index)
        onSelectionChanged?.invoke(selected.size)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder =
        MediaViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false),
            thumbnails
        )

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, selected.contains(item.path))
        holder.itemView.setOnClickListener {
            if (selectionMode) toggleSelection(item) else onItemClick?.invoke(item)
        }
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) setSelectionMode(true)
            toggleSelection(item)
            true
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

            // 用 tag 记录当前绑定的路径：异步缩略图回来时若已被复用则丢弃，避免错图
            thumb.tag = item.path
            thumb.setImageDrawable(null)
            thumb.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.divider))
            thumbnails.load(item) { bitmap ->
                if (thumb.tag == item.path) thumb.setImageBitmap(bitmap)
            }
        }
    }
}
