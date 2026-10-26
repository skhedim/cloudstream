package com.lagradost.cloudstream3.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.SearchLoadMoreBinding
import com.lagradost.cloudstream3.ui.search.aggregation.AggregatedSearchResponse
import com.lagradost.cloudstream3.databinding.SearchResultGridBinding
import com.lagradost.cloudstream3.databinding.SearchResultGridExpandedBinding
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.utils.UIHelper.isBottomLayout
import kotlin.math.roundToInt

/** Click */
const val SEARCH_ACTION_LOAD = 0

/** Long press */
const val SEARCH_ACTION_SHOW_METADATA = 1
const val SEARCH_ACTION_PLAY_FILE = 2
const val SEARCH_ACTION_FOCUSED = 4

class SearchClickCallback(
    val action: Int,
    val view: View,
    val position: Int,
    val card: SearchResponse
)

class SearchAdapter(
    private val resView: AutofitRecyclerView,
    private val isHorizontal:Boolean = false,
    private val loadMore: (() -> Unit)?,
    private val clickCallback: (SearchClickCallback) -> Unit,
) : NoStateAdapter<SearchResponse>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    if (a is AggregatedSearchResponse && b is AggregatedSearchResponse) {
        a.media.id == b.media.id
    } else if (a is AggregatedSearchResponse || b is AggregatedSearchResponse) {
        false
    } else if (a.id != null || b.id != null) {
        a.id == b.id
    } else {
        a.name == b.name
    }
})) {
    // Keep the existing constructor (including Kotlin's default-argument bridge) for extensions.
    constructor(
        resView: AutofitRecyclerView,
        isHorizontal: Boolean = false,
        clickCallback: (SearchClickCallback) -> Unit,
    ) : this(resView, isHorizontal, null, clickCallback)

    companion object {
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 10) }
    }

    var hasNext: Boolean = false
    private var loadingMore = false
    private var hasFailures = false
    override val footers: Int get() = if (loadMore == null) 0 else 1

    fun configurePagingSpan() {
        if (loadMore == null) return
        val manager = resView.layoutManager as? GridLayoutManager ?: return
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position >= immutableCurrentList.size) manager.spanCount else 1
        }
    }

    fun updatePaging(hasNext: Boolean, loading: Boolean, failed: Boolean = hasFailures) {
        this.hasNext = hasNext
        loadingMore = loading
        hasFailures = failed
        if (loadMore != null) notifyItemChanged(immutableCurrentList.size)
    }

    override fun onCreateFooter(parent: ViewGroup): ViewHolderState<Any> =
        ViewHolderState(SearchLoadMoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindFooter(holder: ViewHolderState<Any>) {
        val binding = holder.view as SearchLoadMoreBinding
        binding.loadMore.visibility = if (hasNext || loadingMore) View.VISIBLE else View.GONE
        binding.loadMore.isEnabled = !loadingMore
        binding.loadMore.setText(when {
            loadingMore -> R.string.loading
            hasFailures -> R.string.search_retry_providers
            else -> R.string.search_load_more
        })
        binding.loadMore.setOnClickListener { loadMore?.invoke() }
    }

    private val coverRatio = if(isHorizontal) 1.8 else 0.68

    private val coverHeight: Int get() = (resView.itemWidth / coverRatio).roundToInt()

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val inflater = LayoutInflater.from(parent.context)

        val layout =
            if (parent.context.isBottomLayout()) SearchResultGridExpandedBinding.inflate(
                inflater,
                parent,
                false
            ) else SearchResultGridBinding.inflate(
                inflater,
                parent,
                false
            )
        return ViewHolderState(layout)
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        clearImage(
            when (val binding = holder.view) {
                is SearchResultGridExpandedBinding -> binding.imageView
                is SearchResultGridBinding -> binding.imageView
                else -> null
            }
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: SearchResponse, position: Int) {
        val imageView = when (val binding = holder.view) {
            is SearchResultGridExpandedBinding -> binding.imageView
            is SearchResultGridBinding -> binding.imageView
            else -> null
        }

        if (imageView != null) {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                coverHeight
            )
            if (imageView.layoutParams.width != params.width || imageView.layoutParams.height != params.height) {
                imageView.layoutParams = params
            }
        }
        SearchResultBuilder.bind(clickCallback, item, position, holder.view.root)
    }
}
