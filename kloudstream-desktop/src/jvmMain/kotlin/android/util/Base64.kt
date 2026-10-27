package android.util

/**
 * Android's Base64 on top of java.util.Base64, with Android's flags: decoding ignores whitespace
 * and missing padding like Android does; DEFAULT encoding wraps lines every 76 characters.
 */
class Base64 {
    companion object {
        const val DEFAULT = 0
        const val NO_PADDING = 1
        const val NO_WRAP = 2
        const val CRLF = 4
        const val URL_SAFE = 8
        const val NO_CLOSE = 16

        @JvmStatic fun decode(str: String?, flags: Int): ByteArray = decode(str?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0), flags)

        @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray = decode(input, 0, input.size, flags)

        @JvmStatic fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
            // Android accepts both alphabets regardless of URL_SAFE and stops at the first '='.
            val clean = StringBuilder(len)
            for (i in offset until offset + len) {
                when (val c = input[i].toInt().toChar()) {
                    '-' -> clean.append('+')
                    '_' -> clean.append('/')
                    '=' -> break
                    in 'A'..'Z', in 'a'..'z', in '0'..'9', '+', '/' -> clean.append(c)
                    ' ', '\n', '\r', '\t' -> Unit
                    else -> throw IllegalArgumentException("bad base-64")
                }
            }
            if (clean.length % 4 == 1) throw IllegalArgumentException("bad base-64")
            return java.util.Base64.getDecoder().decode(clean.toString().padEnd((clean.length + 3) / 4 * 4, '='))
        }

        @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String = String(encode(input, flags), Charsets.US_ASCII)

        @JvmStatic fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String =
            encodeToString(input.copyOfRange(offset, offset + len), flags)

        @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray {
            val base = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
            var text = (if (flags and NO_PADDING != 0) base.withoutPadding() else base).encodeToString(input)
            if (flags and NO_WRAP == 0 && text.isNotEmpty()) {
                val newline = if (flags and CRLF != 0) "\r\n" else "\n"
                text = text.chunked(76).joinToString(newline, postfix = newline)
            }
            return text.toByteArray(Charsets.US_ASCII)
        }
    }
}

class TypedValue {
    companion object {
        const val COMPLEX_UNIT_DIP = 1
        const val COMPLEX_UNIT_SP = 2
        @JvmStatic fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics?): Float = value
    }
}
