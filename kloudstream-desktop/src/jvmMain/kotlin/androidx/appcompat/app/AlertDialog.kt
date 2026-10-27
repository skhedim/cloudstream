package androidx.appcompat.app

import android.content.Context
import android.content.DialogInterface
import android.view.View

/** Dialogs from extension settings: built but never shown on desktop. */
open class AlertDialog : DialogInterface {
    open fun show() {}
    override fun dismiss() {}

    open class Builder(context: Context?) {
        open fun setTitle(title: CharSequence?): Builder = this
        open fun setMessage(message: CharSequence?): Builder = this
        open fun setView(view: View?): Builder = this
        open fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        open fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        open fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        open fun create(): AlertDialog = AlertDialog()
        open fun show(): AlertDialog = create()
    }
}
