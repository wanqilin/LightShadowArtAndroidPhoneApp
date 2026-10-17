package com.ShowSeen.lightshadowart.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ShowSeen.lightshadowart.mobile.net.UploadSource

enum class UploadStatus { WAITING, UPLOADING, DONE, FAILED }

/** Upload list item: not a data class, keeps identity equality so it can be updated by position */
class UploadItem(val source: UploadSource) {
    var status: UploadStatus = UploadStatus.WAITING

    /** -1 means unknown total size, so the progress bar runs indeterminate */
    var progress: Int = -1

    /** Media type after success, error message after failure */
    var detail: String? = null
}

class UploadAdapter : RecyclerView.Adapter<UploadAdapter.UploadViewHolder>() {

    private val items = mutableListOf<UploadItem>()

    fun addAll(newItems: List<UploadItem>) {
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    fun refresh(position: Int) {
        if (position in items.indices) notifyItemChanged(position)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UploadViewHolder =
        UploadViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_upload, parent, false)
        )

    override fun onBindViewHolder(holder: UploadViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class UploadViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_status)
        private val progress: LinearProgressIndicator = itemView.findViewById(R.id.progress)

        fun bind(item: UploadItem) {
            val context = itemView.context
            tvName.text = item.source.displayName

            when (item.status) {
                UploadStatus.WAITING -> {
                    tvStatus.setTextColor(secondary())
                    tvStatus.text = context.getString(R.string.upload_status_waiting)
                    progress.isVisible = false
                }

                UploadStatus.UPLOADING -> {
                    tvStatus.setTextColor(secondary())
                    val determinate = item.progress in 0..100
                    tvStatus.text = if (determinate) {
                        context.getString(R.string.upload_status_uploading, item.progress)
                    } else {
                        context.getString(R.string.upload_status_uploading_unknown)
                    }
                    progress.isVisible = true
                    progress.isIndeterminate = !determinate
                    if (determinate) progress.setProgressCompat(item.progress, true)
                }

                UploadStatus.DONE -> {
                    tvStatus.setTextColor(ContextCompat.getColor(context, R.color.success))
                    tvStatus.text = context.getString(R.string.upload_status_done, item.detail.orEmpty())
                    progress.isVisible = false
                }

                UploadStatus.FAILED -> {
                    tvStatus.setTextColor(ContextCompat.getColor(context, R.color.error))
                    tvStatus.text = context.getString(R.string.upload_status_failed, item.detail.orEmpty())
                    progress.isVisible = false
                }
            }
        }

        private fun secondary(): Int =
            ContextCompat.getColor(itemView.context, R.color.text_secondary)
    }
}
