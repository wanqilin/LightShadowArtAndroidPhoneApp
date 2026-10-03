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
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private val selected = mutableSetOf<String>()

    /** 展开后的展示行：启用 [groupByDate] 时会在每个日期前插入一条日期标题 */
    private val rows = mutableListOf<Row>()

    /** 多选模式：开启后点击为勾选，关闭后点击为切播放 */
    var selectionMode: Boolean = false
        private set

    /** 是否按日期分组展示（日期视图），标题显示分组键 yyyy-MM-dd */
    var groupByDate: Boolean = false
        private set

    var onItemClick: ((MediaItem) -> Unit)? = null
    var onItemLongClick: ((MediaItem) -> Unit)? = null

    /** 选择集变化时回调，供界面刷新「删除」按钮可用态 */
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun submit(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        selected.retainAll(items.map { it.path }.toSet())
        rebuildRows()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    /** 切换是否按日期分组（日期视图插入日期标题，其余视图不插入） */
    fun setGroupByDate(enabled: Boolean) {
        if (groupByDate == enabled) return
        groupByDate = enabled
        rebuildRows()
        notifyDataSetChanged()
    }

    /** 依据 [groupByDate] 展开展示行；同一日期仅在首条前插入一次标题 */
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
                    if (!selectionMode) setSelectionMode(true)
                    toggleSelection(item)
                    true
                }
            }
        }
    }

    /** 展示行：日期标题或媒体条目 */
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

            // 用 tag 记录当前绑定的路径：异步缩略图回来时若已被复用则丢弃，避免错图
            thumb.tag = item.path
            thumb.setImageDrawable(null)
            thumb.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.divider))
            thumbnails.load(item) { bitmap ->
                if (thumb.tag == item.path) thumb.setImageBitmap(bitmap)
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
    }
}
