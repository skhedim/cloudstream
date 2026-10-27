package com.lagradost.cloudstream3.ui.result

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.FocusDirection
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

const val FOCUS_SELF = View.NO_ID - 1
const val FOCUS_INHERIT = FOCUS_SELF - 1

fun RecyclerView?.setLinearListLayout(
    isHorizontal: Boolean = true,
    nextLeft: Int = FOCUS_INHERIT,
    nextRight: Int = FOCUS_INHERIT,
    nextUp: Int = FOCUS_INHERIT,
    nextDown: Int = FOCUS_INHERIT
) {
    if (this == null) return
    val ctx = this.context ?: return
    this.layoutManager = (this.layoutManager as? LinearListLayout ?: LinearListLayout(ctx)).apply {
        if (isHorizontal) setHorizontal() else setVertical()
        nextFocusLeft =
            if (nextLeft == FOCUS_INHERIT) this@setLinearListLayout.nextFocusLeftId else nextLeft
        nextFocusRight =
            if (nextRight == FOCUS_INHERIT) this@setLinearListLayout.nextFocusRightId else nextRight
        nextFocusUp =
            if (nextUp == FOCUS_INHERIT) this@setLinearListLayout.nextFocusUpId else nextUp
        nextFocusDown =
            if (nextDown == FOCUS_INHERIT) this@setLinearListLayout.nextFocusDownId else nextDown
    }
}

open class LinearListLayout(context: Context?) :
    LinearLayoutManager(context) {

    var nextFocusLeft: Int = View.NO_ID
    var nextFocusRight: Int = View.NO_ID
    var nextFocusUp: Int = View.NO_ID
    var nextFocusDown: Int = View.NO_ID

    fun setHorizontal() {
        orientation = HORIZONTAL
    }

    fun setVertical() {
        orientation = VERTICAL
    }

    private fun getCorrectParent(focused: View?): View? {
        if (focused == null) return null
        var current: View? = focused
        val last: ArrayList<View> = arrayListOf(focused)
        while (current != null && current !is RecyclerView) {
            current = (current.parent as? View?)?.also { last.add(it) }
        }
        return last.getOrNull(last.count() - 2)
    }

    private fun getPosition(view: View?): Int? {
        return (view?.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition
    }

    private fun getViewFromPos(pos: Int): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if ((child?.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition == pos) {
                return child
            }
        }
        return null
        //return recyclerView.children.firstOrNull { child -> (child.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition == pos) }
    }

    /*
    private fun scrollTo(position: Int) {
        val linearSmoothScroller = LinearSmoothScroller(recyclerView.context)
        linearSmoothScroller.targetPosition = position
        startSmoothScroll(linearSmoothScroller)
    }*/

    /** from the current focus go to a direction */
    private fun getNextDirection(focused: View?, direction: FocusDirection): View? {
        val id = when (direction) {
            FocusDirection.Start -> if (isLayoutRTL) nextFocusRight else nextFocusLeft
            FocusDirection.End -> if (isLayoutRTL) nextFocusLeft else nextFocusRight
            FocusDirection.Up -> nextFocusUp
            FocusDirection.Down -> nextFocusDown
        }

        return when (id) {
            View.NO_ID -> null
            FOCUS_SELF -> focused
            else -> CommonActivity.continueGetNextFocus(
                activity ?: focused,
                focused ?: return null,
                direction,
                id
            )
        }
    }

    fun redirectRecycleToFirstItem(focused: View): View? {
        return when (focused) {
            is RecyclerView -> {
                (focused.layoutManager as? LinearListLayout)?.let { focusedLayoutManager ->
                    // On TV, back to the card last focused in that list (rows keep their place).
                    if (isLayout(TV) && focusedLayoutManager.orientation == HORIZONTAL)
                        focusedLayoutManager.landingCard(focused)?.let { return it }
                    val firstPosition = focusedLayoutManager.findFirstVisibleItemPosition()
                    val firstView = focusedLayoutManager.findViewByPosition(firstPosition)
                    firstView
                } ?: focused
            }

            else -> focused
        }
    }

    /** Adapter position of the card last focused in this list, to come back to it from another row. */
    var lastFocusedPosition = RecyclerView.NO_POSITION

    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean {
        lastFocusedPosition =
            (child.layoutParams as? RecyclerView.LayoutParams)?.absoluteAdapterPosition ?: RecyclerView.NO_POSITION
        return super.onRequestChildFocus(parent, state, child, focused)
    }

    /**
     * The card to land on when entering this horizontal [list] from another row: its last focused
     * card while on screen, else its first fully visible card.
     */
    private fun landingCard(list: RecyclerView): View? {
        val start = list.paddingLeft
        val end = list.width - list.paddingRight
        fun View.fullyVisible() = getDecoratedLeft(this) >= start - 8 && getDecoratedRight(this) <= end + 8
        findViewByPosition(lastFocusedPosition)?.takeIf { it.isFocusable && it.fullyVisible() }?.let { return it }
        val cards = (0 until childCount).mapNotNull { getChildAt(it) }.filter { it.isFocusable }
        return cards.filter { it.fullyVisible() }.minByOrNull { getDecoratedLeft(it) }
            ?: cards.minByOrNull { kotlin.math.abs(getDecoratedLeft(it) - start) }
    }

    /** [landingCard] of the horizontal row containing [view] (not [from]), or null outside such a row. */
    private fun leadingCardOfRow(view: View, from: RecyclerView?): View? {
        var current: View? = view
        while (current != null) {
            val list = current.parent as? RecyclerView
            val layout = list?.layoutManager as? LinearListLayout
            if (list != null && layout != null && layout.orientation == HORIZONTAL) {
                return if (list === from) null else layout.landingCard(list)
            }
            current = current.parent as? View
        }
        return null
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val dir = if (orientation == HORIZONTAL) {
            if (direction == View.FOCUS_DOWN) getNextDirection(
                focused,
                FocusDirection.Down
            )?.let { newFocus ->
                return redirectRecycleToFirstItem(newFocus)
            }
            if (direction == View.FOCUS_UP) getNextDirection(
                focused,
                FocusDirection.Up
            )?.let { newFocus ->
                return redirectRecycleToFirstItem(newFocus)
            }

            if (direction == View.FOCUS_DOWN || direction == View.FOCUS_UP) {
                // On TV the focused card is pinned to the start of its row. Moving to another
                // row lands on that row's leading card instead of the one geometrically
                // aligned, so no row scrolls and coming back returns to the same card.
                if (isLayout(TV)) {
                    val row = getCorrectParent(focused)?.parent as? RecyclerView
                    val next = (row?.parent as? View)?.focusSearch(direction)
                    if (next != null) return leadingCardOfRow(next, row) ?: next
                    return null
                }
                // This scrolls the recyclerview before doing focus search, which
                // allows the focus search to work better.

                // Without this the recyclerview focus location on the screen
                // would change when scrolling between recyclerviews.
                (focused.parent as? RecyclerView)?.focusSearch(direction)
                return null
            }
            var ret = if (direction == View.FOCUS_RIGHT) 1 else -1
            // only flip on horizontal layout
            if (isLayoutRTL) {
                ret = -ret
            }
            ret
        } else {
            if (direction == View.FOCUS_RIGHT) getNextDirection(
                focused,
                FocusDirection.End
            )?.let { newFocus ->
                return newFocus
            }
            if (direction == View.FOCUS_LEFT) getNextDirection(
                focused,
                FocusDirection.Start
            )?.let { newFocus ->
                return newFocus
            }

            if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) {
                (focused.parent as? RecyclerView)?.focusSearch(direction)
                return null
            }

            //if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) return null
            if (direction == View.FOCUS_DOWN) 1 else -1
        }

        try {
            val position = getPosition(getCorrectParent(focused)) ?: return null
            val lookFor = dir + position

            // if out of bounds then refocus as specified
            return if (lookFor >= itemCount) {
                getNextDirection(
                    focused,
                    if (orientation == HORIZONTAL) FocusDirection.End else FocusDirection.Down
                )
            } else if (lookFor < 0) {
                getNextDirection(
                    focused,
                    if (orientation == HORIZONTAL) FocusDirection.Start else FocusDirection.Up
                )
            } else {
                getViewFromPos(lookFor) ?: run {
                    scrollToPosition(lookFor)
                    null
                }
            }
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: android.graphics.Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        if (isLayout(TV) && orientation == HORIZONTAL) {
            val dx = when {
                isLayoutRTL -> getDecoratedRight(child) - (parent.width - parent.paddingRight)
                else -> getDecoratedLeft(child) - parent.paddingLeft
            }
            return if (dx != 0) {
                when {
                    immediate -> parent.scrollBy(dx, 0)
                    else -> parent.smoothScrollBy(dx, 0)
                }
                true
            } else {
                false
            }
        } else {
            return super.requestChildRectangleOnScreen(
                parent,
                child,
                rect,
                immediate,
                focusedChildVisible
            )
        }
    }

    /*override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean {
        return super.onRequestChildFocus(parent, state, child, focused)
        getPosition(getCorrectParent(focused ?: return true))?.let {
            val startView = findFirstVisibleChildClosestToStart(true,true)
            val endView = findFirstVisibleChildClosestToEnd(true,true)
            val start = getPosition(startView)
            val end = getPosition(endView)
            fill(parent,LayoutState())

            val helper = mOrientationHelper ?: return false
            val laidOutArea: Int = abs(
                helper.getDecoratedEnd(startView)
                        - helper.getDecoratedStart(endView)
            )
            val itemRange: Int = abs(
                (start
                        - end)
            ) + 1

            val avgSizePerRow = laidOutArea.toFloat() / itemRange

            return Math.round(
                itemsBefore * avgSizePerRow + ((orientation.getStartAfterPadding()
                        - orientation.getDecoratedStart(startChild)))
            )
            recyclerView.scrollToPosition(it)
        }
        return true*/

    //return super.onRequestChildFocus(parent, state, child, focused)
    /* if (focused == null || focused == child) {
         return super.onRequestChildFocus(parent, state, child, focused)
     }

     try {
         val pos = getPosition(getCorrectParent(focused) ?: return true)
         scrollToPosition(pos)
     } catch (e: Exception) {
         logError(e)
     }
     return true
}*/
}