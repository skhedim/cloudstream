package android.graphics

class Color {
    companion object {
        const val BLACK = -0x1000000
        const val WHITE = -0x1
        const val TRANSPARENT = 0
        const val RED = -0x10000
        const val GREEN = -0xff0100
        const val BLUE = -0xffff01
        const val GRAY = -0x777778

        @JvmStatic fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        @JvmStatic fun rgb(red: Int, green: Int, blue: Int): Int = argb(255, red, green, blue)

        /** `#RRGGBB`, `#AARRGGBB` and a few names, like Android. */
        @JvmStatic fun parseColor(colorString: String): Int {
            if (colorString.startsWith("#")) {
                val value = colorString.substring(1).toLong(16)
                return when (colorString.length) {
                    7 -> (value or 0xFF000000).toInt()
                    9 -> value.toInt()
                    else -> throw IllegalArgumentException("Unknown color")
                }
            }
            return when (colorString.lowercase()) {
                "black" -> BLACK; "white" -> WHITE; "red" -> RED; "green" -> GREEN; "blue" -> BLUE
                "gray", "grey" -> GRAY; "transparent" -> TRANSPARENT
                else -> throw IllegalArgumentException("Unknown color")
            }
        }
    }
}
