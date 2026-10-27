package android.graphics.drawable

open class Drawable

open class GradientDrawable : Drawable() {
    open fun setColor(argb: Int) {}
    open fun setShape(shape: Int) {}
    open fun setCornerRadius(radius: Float) {}
    open fun setStroke(width: Int, color: Int) {}

    companion object {
        const val RECTANGLE = 0
        const val OVAL = 1
    }
}

open class ColorDrawable(open var color: Int = 0) : Drawable()
