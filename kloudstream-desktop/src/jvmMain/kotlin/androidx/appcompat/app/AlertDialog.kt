package androidx.appcompat.app

import android.content.Context
import android.content.DialogInterface
import android.view.View
import com.kloudstream.desktop.extensions.ExtensionPrompts

/** Dialogs from extensions (settings, donation requests, notices): built, recorded, never shown on desktop. */
open class AlertDialog(private val text: String = "") : DialogInterface {
    open fun show() = ExtensionPrompts.record("dialog", text)
    override fun dismiss() {}

    open class Builder(context: Context?) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        open fun setTitle(title: CharSequence?): Builder = apply { this.title = title }
        open fun setMessage(message: CharSequence?): Builder = apply { this.message = message }
        open fun setView(view: View?): Builder = this
        open fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        open fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        open fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder = this
        open fun create(): AlertDialog = AlertDialog(listOfNotNull(title, message).joinToString(" — "))
        open fun show(): AlertDialog = create().also { it.show() }
    }
}
