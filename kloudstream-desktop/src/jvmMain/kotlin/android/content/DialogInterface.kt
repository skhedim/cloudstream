package android.content

interface DialogInterface {
    fun dismiss()
    fun cancel() = dismiss()

    fun interface OnClickListener {
        fun onClick(dialog: DialogInterface?, which: Int)
    }

    companion object {
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_NEUTRAL = -3
    }
}
