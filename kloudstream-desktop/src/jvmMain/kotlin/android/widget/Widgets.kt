package android.widget

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup

open class TextView(context: Context?) : View(context) {
    open fun setText(text: CharSequence?) {}
    open fun setTextColor(color: Int) {}
    open fun setTextSize(size: Float) {}
    open fun setGravity(gravity: Int) {}
}

open class Button(context: Context?) : TextView(context)

open class CompoundButton(context: Context?) : Button(context) {
    private var checked = false
    open fun setChecked(checked: Boolean) { this.checked = checked }
    open fun isChecked(): Boolean = checked
    open fun setButtonTintList(tint: ColorStateList?) {}
    open fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {}

    fun interface OnCheckedChangeListener { fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) }
}

open class CheckBox(context: Context?) : CompoundButton(context)

open class LinearLayout(context: Context?) : ViewGroup(context) {
    open fun setOrientation(orientation: Int) {}
    open fun setGravity(gravity: Int) {}

    open class LayoutParams : ViewGroup.MarginLayoutParams {
        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, weight: Float) : super(width, height)
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

open class Toast {
    open fun show() {}

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        @JvmStatic fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast = Toast()
    }
}
