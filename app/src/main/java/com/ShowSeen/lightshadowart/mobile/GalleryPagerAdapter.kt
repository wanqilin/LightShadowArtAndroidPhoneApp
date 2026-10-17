package com.ShowSeen.lightshadowart.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * ViewPager2 bridge for the Space page (UI PRD §3.6 B).
 *
 * ViewPager2 only understands a RecyclerView.Adapter, so every gallery view is a page that holds its
 * own RecyclerView. The page supplies the layout manager, the media adapter and the listeners, which
 * keeps the pager free of gallery logic.
 */
class GalleryPagerAdapter(
    private val pageCount: Int,
    private val onPageCreated: (position: Int, list: RecyclerView) -> Unit
) : RecyclerView.Adapter<GalleryPagerAdapter.PageHolder>() {

    class PageHolder(val list: RecyclerView) : RecyclerView.ViewHolder(list)

    override fun getItemCount(): Int = pageCount

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val list = LayoutInflater.from(parent.context)
            .inflate(R.layout.page_gallery, parent, false) as RecyclerView
        onPageCreated(viewType, list)
        return PageHolder(list)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit
}
