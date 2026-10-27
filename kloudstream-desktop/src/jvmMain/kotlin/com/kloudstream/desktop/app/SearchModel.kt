package com.kloudstream.desktop.app

import com.lagradost.cloudstream4.aggregation.AggregationPlatform
import com.lagradost.cloudstream4.aggregation.CanonicalSearchEngine
import com.lagradost.cloudstream4.aggregation.CanonicalSearchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Unified search over every provider (the shared canonical engine), searched as the user types. */
class SearchModel(private val scope: CoroutineScope) {
    private val engine = CanonicalSearchEngine(scope)
    val state: StateFlow<CanonicalSearchState> get() = engine.state
    private var pending: Job? = null
    var query: String = ""
        private set

    fun type(text: String) {
        query = text
        pending?.cancel()
        pending = scope.launch {
            delay(450)
            AppModel.extensionsReady.await()
            if (text.trim().length < 2) engine.clear() else engine.search(text, AggregationPlatform.registry.providers())
        }
    }

    fun loadMore() = engine.loadMore()
}
