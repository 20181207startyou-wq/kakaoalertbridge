package com.mgad.kakaoalertbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoParticipateRetryTrackerTest {

    private fun tracker() = AutoParticipateRetryTracker(maxAttempts = 3, cooldownMs = 5 * 60 * 1000L)

    @Test
    fun success_marks_completed_no_retry() {
        val t = tracker()
        assertEquals(listOf(1), t.admit(listOf(1), 0))
        t.onStarted(1, 0)
        assertFalse("성공은 재시도하지 않는다", t.onFinished(1, AutoParticipateRetryTracker.Outcome.SUCCESS))
        // 완료된 건은 다시 시도 불가
        assertFalse(t.canAttempt(1, 10_000_000))
        assertTrue(t.admit(listOf(1), 10_000_000).isEmpty())
    }

    @Test
    fun blocked_marks_completed_no_retry() {
        val t = tracker()
        t.admit(listOf(2), 0); t.onStarted(2, 0)
        assertFalse("참여제한은 재시도하지 않는다", t.onFinished(2, AutoParticipateRetryTracker.Outcome.BLOCKED))
        assertFalse(t.canAttempt(2, 10_000_000))
    }

    @Test
    fun failure_is_retriable_until_max_attempts() {
        val t = tracker()
        // 1차 시도 실패 → 재시도 가능
        t.admit(listOf(3), 0); t.onStarted(3, 0)
        assertTrue(t.onFinished(3, AutoParticipateRetryTracker.Outcome.FAILED))
        // 쿨다운 전엔 시도 불가
        assertFalse(t.canAttempt(3, 60_000))
        // 쿨다운 후 2차 시도
        val now2 = 5 * 60 * 1000L + 1
        assertTrue(t.canAttempt(3, now2))
        t.admit(listOf(3), now2); t.onStarted(3, now2)
        assertTrue(t.onFinished(3, AutoParticipateRetryTracker.Outcome.FAILED))
        // 쿨다운 후 3차(마지막) 시도
        val now3 = now2 + 5 * 60 * 1000L + 1
        t.admit(listOf(3), now3); t.onStarted(3, now3)
        // 3회째 실패 → 더 이상 재시도 없음
        assertFalse("최대 시도 소진 후엔 재시도 없음", t.onFinished(3, AutoParticipateRetryTracker.Outcome.FAILED))
        assertFalse(t.canAttempt(3, now3 + 10 * 60 * 1000L))
        assertEquals(3, t.attemptsOf(3))
    }

    @Test
    fun inflight_blocks_duplicate_admit() {
        val t = tracker()
        assertEquals(listOf(4), t.admit(listOf(4), 0))
        // 진행 중(inFlight)인 동안 같은 id 중복 트리거는 큐에 또 들어가지 않는다
        assertTrue(t.admit(listOf(4), 1000).isEmpty())
        t.onStarted(4, 0)
        t.onFinished(4, AutoParticipateRetryTracker.Outcome.FAILED)
        // 끝난 뒤(쿨다운 전)에도 중복 방지는 쿨다운으로 유지
        assertTrue(t.admit(listOf(4), 2000).isEmpty())
    }

    @Test
    fun admit_filters_mixed_batch() {
        val t = tracker()
        // 5는 완료, 6은 진행중, 7은 신규
        t.admit(listOf(5), 0); t.onStarted(5, 0); t.onFinished(5, AutoParticipateRetryTracker.Outcome.SUCCESS)
        t.admit(listOf(6), 0) // inFlight
        val admitted = t.admit(listOf(5, 6, 7), 1000)
        assertEquals("신규 7만 통과", listOf(7), admitted)
    }
}
