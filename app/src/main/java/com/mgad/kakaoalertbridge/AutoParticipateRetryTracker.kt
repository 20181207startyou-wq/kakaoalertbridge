package com.mgad.kakaoalertbridge

// 자동참여 시도의 재시도/완료 상태를 추적하는 순수 로직(안드로이드 의존 없음 - 단위 테스트 가능).
//
// 기존 버그(2026-11-18 수정): PartnersAutoParticipateService가 call_id를 단일 Set(processedCallIds)에
// 넣어, "성공"이든 "실패"든 한 번 시도하면 영원히 다시 시도하지 않았다. 그래서 일시적 실패(파트너스
// 앱 콜드스타트 지연, 잔존 모달 등)로 끝난 콜이 서버가 다시 트리거해도 무조건 스킵됐다.
//
// 새 규칙:
//  - 성공(SUCCESS) 또는 참여제한(BLOCKED, 간판의품격 미상담 제한)은 완료로 보고 다시 시도하지 않는다.
//  - 실패(FAILED)는 최대 MAX_ATTEMPTS회까지, 매 시도 사이 최소 COOLDOWN_MS 간격으로 재시도한다.
//  - 진행 중(inFlight)인 call_id는 중복 트리거로 큐에 또 들어가지 않게 막는다.
//
// 시계(now)는 호출부가 SystemClock.elapsedRealtime()을 넘겨줘 테스트에서 주입 가능하게 한다.
class AutoParticipateRetryTracker(
    val maxAttempts: Int = 3,
    val cooldownMs: Long = 5 * 60 * 1000L,
) {
    enum class Outcome { SUCCESS, BLOCKED, FAILED }

    private class State {
        var attempts: Int = 0
        var lastAttemptMs: Long = 0
        var completed: Boolean = false
        var inFlight: Boolean = false
    }

    private val states = mutableMapOf<Int, State>()

    @Synchronized
    private fun stateOf(callId: Int) = states.getOrPut(callId) { State() }

    // 이 call_id를 지금 시도해도 되는가(큐에 넣어도 되는가)? 완료/진행중/최대시도초과/쿨다운미경과면 false.
    @Synchronized
    fun canAttempt(callId: Int, nowMs: Long): Boolean {
        val s = states[callId] ?: return true
        if (s.completed || s.inFlight) return false
        if (s.attempts >= maxAttempts) return false
        if (s.attempts > 0 && nowMs - s.lastAttemptMs < cooldownMs) return false
        return true
    }

    // 트리거로 들어온 call_id들 중 지금 시도 가능한 것만 골라 inFlight로 표시하고 돌려준다.
    @Synchronized
    fun admit(callIds: List<Int>, nowMs: Long): List<Int> {
        val admitted = mutableListOf<Int>()
        for (id in callIds) {
            if (canAttempt(id, nowMs)) {
                stateOf(id).inFlight = true
                admitted.add(id)
            }
        }
        return admitted
    }

    // 실제 플로우가 시작될 때 - 시도 횟수 증가, 마지막 시도 시각 기록.
    @Synchronized
    fun onStarted(callId: Int, nowMs: Long) {
        val s = stateOf(callId)
        s.attempts += 1
        s.lastAttemptMs = nowMs
    }

    // 플로우 종료 - 결과 기록. 성공/참여제한은 완료 처리(재시도 안 함). 실패는 재시도 여지를 남긴다.
    // 재시도가 더 가능하면(실패 + 최대 미만) true를 돌려준다(호출부가 쿨다운 뒤 재시도 예약).
    @Synchronized
    fun onFinished(callId: Int, outcome: Outcome): Boolean {
        val s = stateOf(callId)
        s.inFlight = false
        return when (outcome) {
            Outcome.SUCCESS, Outcome.BLOCKED -> { s.completed = true; false }
            Outcome.FAILED -> s.attempts < maxAttempts
        }
    }

    @Synchronized
    fun attemptsOf(callId: Int): Int = states[callId]?.attempts ?: 0
}
