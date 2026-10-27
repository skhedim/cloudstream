package android.view

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Inert view classes: extensions reference them in settings screens, which desktop does not show.
 * They exist so that extension classes link; nothing is ever drawn.
 */
open class View(private val context: Context?) {
    private var enabled = true
    open fun getContext(): Context? = context
    open fun findViewById(id: Int): View? = null
    open fun setId(id: Int) {}
    open fun getId(): Int = 0
    open fun setLayoutParams(params: ViewGroup.LayoutParams?) {}
    open fun setOnClickListener(listener: OnClickListener?) {}
    open fun setOnFocusChangeListener(listener: OnFocusChangeListener?) {}
    open fun setFocusable(focusable: Boolean) {}
    open fun setFocusableInTouchMode(focusable: Boolean) {}
    open fun requestFocus(): Boolean = false
    open fun setBackground(background: Drawable?) {}
    open fun setBackgroundColor(color: Int) {}
    open fun isEnabled(): Boolean = enabled
    open fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    open fun setVisibility(visibility: Int) {}
    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    open fun post(action: Runnable): Boolean { action.run(); return true }

    fun interface OnClickListener { fun onClick(v: View?) }
    fun interface OnFocusChangeListener { fun onFocusChange(v: View?, hasFocus: Boolean) }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        private val nextId = java.util.concurrent.atomic.AtomicInteger(1)
        @JvmStatic fun generateViewId(): Int = nextId.getAndIncrement()
    }
}

open class ViewGroup(context: Context?) : View(context) {
    open fun addView(child: View?) {}
    open fun removeAllViews() {}

    open class LayoutParams(@JvmField var width: Int, @JvmField var height: Int) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }

    open class MarginLayoutParams(width: Int, height: Int) : LayoutParams(width, height) {
        open fun setMarginEnd(marginEnd: Int) {}
        open fun setMarginStart(marginStart: Int) {}
        open fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {}
    }
}

open class LayoutInflater private constructor() {
    open fun inflate(resource: Int, root: ViewGroup?): View = throw UnsupportedOperationException("No layouts on desktop")
    open fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean): View = inflate(resource, root)
    open fun inflate(parser: org.xmlpull.v1.XmlPullParser?, root: ViewGroup?): View = throw UnsupportedOperationException("No layouts on desktop")

    companion object {
        @JvmStatic fun from(context: Context?): LayoutInflater = LayoutInflater()
    }
}
