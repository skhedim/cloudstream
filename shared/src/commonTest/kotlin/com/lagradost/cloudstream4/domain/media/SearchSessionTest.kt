package com.lagradost.cloudstream4.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchSessionTest {
    private val a = ProviderKey("A", "https://a.example")
    private val b = ProviderKey("B", "https://b.example")
    private fun session() = SearchSession<String> { it }

    @Test fun lateResponseAfterNewQueryOrClearIsRejected() {
        val session = session()
        session.begin(listOf(a))
        val old = assertNotNull(session.request(a))
        session.begin(listOf(a))
        val current = assertNotNull(session.request(a))
        assertFalse(session.accept(old, listOf("old"), true))
        assertFalse(session.fail(old))
        assertTrue(session.accept(current, listOf("new"), false))
        assertEquals(listOf("new"), session.snapshot().getValue(a).items)
        session.begin(emptyList())
        assertFalse(session.accept(current, listOf("late"), false))
        assertTrue(session.snapshot().isEmpty())
    }

    @Test fun requestsCannotOverlapAndDuplicateCallbacksAreIgnored() {
        val session = session()
        session.begin(listOf(a))
        val request = assertNotNull(session.request(a))
        assertNull(session.request(a))
        assertTrue(session.accept(request, listOf("one"), true))
        assertFalse(session.accept(request, listOf("one"), true))
        assertEquals(2, session.request(a)?.page)
    }

    @Test fun failedNextPageRetainsResultsAndRetriesTheSamePage() {
        val session = session()
        session.begin(listOf(a))
        session.accept(assertNotNull(session.request(a)), listOf("one"), true)
        val next = assertNotNull(session.request(a))
        session.fail(next)
        assertTrue(session.hasFailures)
        assertEquals(listOf("one"), session.snapshot().getValue(a).items)
        val retry = assertNotNull(session.request(a))
        assertEquals(2, retry.page)
        assertFalse(session.accept(next, listOf("late failed attempt"), false))
        assertFalse(session.fail(next))
        session.accept(retry, listOf("one", "two"), false)
        assertFalse(session.hasFailures)
        assertFalse(session.hasNext)
        assertEquals(listOf("one", "two"), session.snapshot().getValue(a).items)
        assertNull(session.request(a))
    }

    @Test fun providerPagesAreIndependentAndCompletionOrderDoesNotChangeRank() {
        val session = session()
        session.begin(listOf(a, b))
        val ar = assertNotNull(session.request(a))
        val br = assertNotNull(session.request(b))
        session.accept(br, listOf("same-url"), false)
        session.accept(ar, listOf("same-url"), true)
        assertEquals(listOf(a, b), session.snapshot().keys.toList())
        assertEquals(2, session.snapshot().values.sumOf { it.items.size })
        assertTrue(session.hasNext)
        assertNull(session.request(b))
        assertEquals(2, session.request(a)?.page)
    }

    @Test fun failedFirstPageCanBeRetriedAndOldSnapshotsStayUnchanged() {
        val session = session()
        session.begin(listOf(a))
        val initial = session.snapshot()
        session.fail(assertNotNull(session.request(a)))
        val retry = assertNotNull(session.request(a))
        assertEquals(1, retry.page)
        session.accept(retry, listOf("one"), false)
        assertEquals(emptyList(), initial.getValue(a).items)
        assertEquals(0, initial.getValue(a).page)
    }
}
