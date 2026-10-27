package androidx.recyclerview.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

open class RecyclerView(context: Context?) : ViewGroup(context) {
    open fun setAdapter(adapter: Adapter<*>?) {}
    open fun setLayoutManager(layout: LayoutManager?) {}
    open fun findViewHolderForAdapterPosition(position: Int): ViewHolder? = null

    abstract class Adapter<VH : ViewHolder> {
        open fun notifyDataSetChanged() {}
    }

    abstract class LayoutManager

    abstract class ViewHolder(@JvmField val itemView: View)
}

open class LinearLayoutManager(context: Context?) : RecyclerView.LayoutManager()
