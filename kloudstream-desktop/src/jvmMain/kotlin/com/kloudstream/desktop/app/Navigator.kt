package com.kloudstream.desktop.app

import com.lagradost.cloudstream4.aggregation.CanonicalDetailRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Downloads : Screen
    /** [title]/[poster] show immediately while the page loads; [autoplay] starts playback once it has. */
    data class Detail(val request: CanonicalDetailRequest, val title: String, val poster: String? = null,
        val autoplay: Boolean = false) : Screen
}

/** A back stack of screens; the top-level destinations replace it. */
class Navigator {
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> get() = _stack
    val current: Screen get() = _stack.value.last()

    fun open(screen: Screen) {
        _stack.value = if (screen is Screen.Detail) _stack.value + screen else listOf(screen)
    }

    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }
}
