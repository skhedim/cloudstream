package android.content

/** Android's preferences interface, as extensions call it. */
interface SharedPreferences {
    fun getAll(): Map<String, *>
    fun getString(key: String?, defValue: String?): String?
    fun getStringSet(key: String?, defValues: Set<String>?): Set<String>?
    fun getInt(key: String?, defValue: Int): Int
    fun getLong(key: String?, defValue: Long): Long
    fun getFloat(key: String?, defValue: Float): Float
    fun getBoolean(key: String?, defValue: Boolean): Boolean
    fun contains(key: String?): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String?, value: String?): Editor
        fun putStringSet(key: String?, values: Set<String>?): Editor
        fun putInt(key: String?, value: Int): Editor
        fun putLong(key: String?, value: Long): Editor
        fun putFloat(key: String?, value: Float): Editor
        fun putBoolean(key: String?, value: Boolean): Editor
        fun remove(key: String?): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

/** Preferences backed by a map; [onChange] persists them. */
open class InMemorySharedPreferences(
    initial: Map<String, Any?> = emptyMap(),
    private val onChange: (Map<String, Any?>) -> Unit = {},
) : SharedPreferences {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>(initial.filterValues { it != null }.mapValues { it.value!! })

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?) =
        (values[key] as? Collection<*>)?.map { it.toString() }?.toSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = (values[key] as? Number)?.toInt() ?: defValue
    override fun getLong(key: String?, defValue: Long) = (values[key] as? Number)?.toLong() ?: defValue
    override fun getFloat(key: String?, defValue: Float) = (values[key] as? Number)?.toFloat() ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = key != null && values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { key?.let { changes[it] = value } }
        override fun putStringSet(key: String?, values: Set<String>?) = apply { key?.let { changes[it] = values?.toList() } }
        override fun putInt(key: String?, value: Int) = apply { key?.let { changes[it] = value } }
        override fun putLong(key: String?, value: Long) = apply { key?.let { changes[it] = value } }
        override fun putFloat(key: String?, value: Float) = apply { key?.let { changes[it] = value } }
        override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { changes[it] = value } }
        override fun remove(key: String?) = apply { key?.let { changes[it] = null } }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            synchronized(values) {
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
            onChange(getAll())
        }
    }
}
