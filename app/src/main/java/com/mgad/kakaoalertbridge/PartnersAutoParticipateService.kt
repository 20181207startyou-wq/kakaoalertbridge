package com.mgad.kakaoalertbridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

// 업무시간 외 "상담참여" 자동확보 - 간판의품격 파트너스 앱에서 견적입찰 탭 -> 신규 항목 ->
// "상담참여" 버튼을 접근성 서비스로 자동 클릭한다. 실기기/에뮬레이터가 연결된 환경이 없어
// 정확한 리소스ID를 확인할 수 없었으므로 명세의 한글 텍스트로 최선을 다해 매칭한다
// (AutoParticipateSettings에서 텍스트/패키지명을 재배포 없이 조정 가능).
//
// 실제 클릭/드라이런 여부는 AutoParticipateSettings.isDryRun()을 트리거 시점에 스냅샷으로
// 읽어 흐름 내내 일관되게 사용한다 - 진행 중에 사용자가 설정을 바꿔도 그 시도의 동작이
// 중간에 바뀌지 않게 하기 위함.
class PartnersAutoParticipateService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoParticipate"
        private const val STEP_TIMEOUT_MS = 8000L
        private const val POLL_INTERVAL_MS = 300L

        private var instance: WeakReference<PartnersAutoParticipateService>? = null
        private val processedCallIds = mutableSetOf<Int>()

        // 접근성 서비스는 한 번에 화면 하나만 조작할 수 있다. 카카오톡이 미읽음 알림을
        // 이어붙여 보내면 한 알림에 여러 고객의 리마인더가 묶여 오는 게 흔해(김정은님 건
        // 2026-09-11 참고) 트리거 한 번에 call_id가 여러 개 들어올 수 있는데, 이걸 병렬로
        // 처리하면 여러 코루틴이 동시에 파트너스 앱을 열고 클릭하면서 서로 화면 상태를
        // 덮어써 전부 실패하거나 엉뚱한 걸 클릭하는 사고가 난다. 그래서 큐에 쌓아 하나씩
        // 순차 처리(이전 항목의 결과 보고까지 끝나야 다음 항목 시작)한다.
        private val pendingQueue = ArrayDeque<Int>()
        private var isProcessing = false

        // businessHours: 이 트리거가 업무시간 중에 일어난 것인지 - 업무시간 외/업무시간 중
        // 토글이 각각 독립적이라, 어느 쪽 창(window)인지에 맞는 토글로 활성 여부를 판단한다.
        fun trigger(context: android.content.Context, callIds: List<Int>, businessHours: Boolean = false) {
            if (callIds.isEmpty()) return
            val enabledForThisWindow = if (businessHours) {
                AutoParticipateSettings.isBusinessHoursEnabled(context)
            } else {
                AutoParticipateSettings.isEnabled(context)
            }
            if (!enabledForThisWindow) {
                Log.d(TAG, "자동참여 비활성 상태(업무시간=$businessHours) - 스킵(call_ids=$callIds)")
                return
            }
            if (!AutoParticipateSettings.isConfigured(context)) {
                Log.w(TAG, "파트너스 앱 패키지명 미설정 - 스킵(call_ids=$callIds)")
                return
            }

            val newIds = synchronized(processedCallIds) {
                callIds.filter { processedCallIds.add(it) }
            }
            if (newIds.isEmpty()) {
                Log.d(TAG, "이미 처리(시도)된 call_ids=$callIds - 중복 트리거 스킵")
                return
            }

            synchronized(pendingQueue) { pendingQueue.addAll(newIds) }
            processNextIfIdle(context)
        }

        private fun processNextIfIdle(context: android.content.Context) {
            val nextId: Int
            synchronized(pendingQueue) {
                if (isProcessing) return
                nextId = pendingQueue.removeFirstOrNull() ?: return
                isProcessing = true
            }
            val dryRun = AutoParticipateSettings.isDryRun(context)
            val svc = instance?.get()
            if (svc == null) {
                Log.w(TAG, "접근성 서비스 비활성(설정에서 켜지지 않음) - 자동참여 스킵(call_id=$nextId)")
                AutoParticipateResultSender.send(nextId, success = false, errorMessage = "접근성 서비스가 켜져있지 않음", dryRun = dryRun)
                synchronized(pendingQueue) { isProcessing = false }
                processNextIfIdle(context)
                return
            }
            svc.runParticipateFlow(nextId, dryRun) {
                synchronized(pendingQueue) { isProcessing = false }
                processNextIfIdle(context)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        Log.d(TAG, "접근성 서비스 연결됨")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // 상태 전이는 폴링(rootInActiveWindow 주기적 조회)으로 처리하므로 이벤트 콜백 자체는
    // 별도 로직이 필요 없음 - 시스템 요구사항상 오버라이드만 해둔다.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "접근성 서비스 인터럽트")
    }

    private fun runParticipateFlow(callId: Int, dryRun: Boolean, onComplete: () -> Unit) {
        scope.launch {
            val packageName = AutoParticipateSettings.getPartnersPackageName(applicationContext)
            val tabText = AutoParticipateSettings.getTabButtonText(applicationContext)
            val participateText = AutoParticipateSettings.getParticipateButtonText(applicationContext)

            // 2026-09-15: 실패 알림에 "몇 번째 단계"인지가 안 남아 있어서, com.classy.ganpoompartner
            // 패키지명 확인 등 엉뚱한 곳을 의심하며 시간을 썼던 문제 - 모든 실패 사유 앞에 단계
            // 번호를 붙여, 다음에 또 실패해도 관리자 알림 문구만 보고 바로 어느 단계인지 알 수 있게 한다.
            val totalSteps = 8
            try {
                Log.d(TAG, "call_id=$callId 시작 - 패키지=$packageName")

                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent == null) {
                    fail(callId, dryRun, 1, totalSteps, "파트너스 앱(${packageName})을 찾을 수 없음")
                    return@launch
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)

                if (!waitForPackageForeground(packageName)) {
                    fail(callId, dryRun, 2, totalSteps, "파트너스 앱 전면 전환 대기 시간 초과")
                    return@launch
                }

                val tabNode = waitForNodeByText(tabText)
                    ?: run { fail(callId, dryRun, 3, totalSteps, "\"$tabText\" 탭을 찾지 못함"); return@launch }
                if (!clickSelfOrClickableAncestor(tabNode)) {
                    fail(callId, dryRun, 4, totalSteps, "\"$tabText\" 탭 클릭 실패(클릭 가능한 노드 없음)")
                    return@launch
                }

                delay(1500)

                val itemNode = waitForFirstClickableExcluding(setOf(tabText))
                    ?: run { fail(callId, dryRun, 5, totalSteps, "견적입찰 목록에서 열 항목을 찾지 못함"); return@launch }
                if (!clickSelfOrClickableAncestor(itemNode)) {
                    fail(callId, dryRun, 6, totalSteps, "목록 항목 클릭 실패(클릭 가능한 노드 없음)")
                    return@launch
                }

                val participateNode = waitForNodeByText(participateText)
                    ?: run { fail(callId, dryRun, 7, totalSteps, "\"$participateText\" 버튼을 찾지 못함"); return@launch }

                if (dryRun) {
                    Log.d(TAG, "[드라이런] call_id=$callId \"$participateText\" 버튼 발견(8/$totalSteps 단계 진입 성공) - 실제 클릭 안 함")
                    AutoParticipateResultSender.send(callId, success = true, errorMessage = null, dryRun = true)
                } else {
                    if (!clickSelfOrClickableAncestor(participateNode)) {
                        fail(callId, dryRun, 8, totalSteps, "\"$participateText\" 버튼 클릭 실패(클릭 가능한 노드 없음)")
                        return@launch
                    }
                    Log.d(TAG, "call_id=$callId \"$participateText\" 클릭 완료(8/$totalSteps 단계 전부 성공)")
                    AutoParticipateResultSender.send(callId, success = true, errorMessage = null, dryRun = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "자동참여 흐름 중 예외(call_id=$callId)", e)
                AutoParticipateResultSender.send(callId, success = false, errorMessage = "예외: ${e.message}", dryRun = dryRun)
            } finally {
                performGlobalAction(GLOBAL_ACTION_HOME)
                onComplete()
            }
        }
    }

    private fun fail(callId: Int, dryRun: Boolean, step: Int, totalSteps: Int, reason: String) {
        val labeled = "[$step/${totalSteps}단계] $reason"
        Log.w(TAG, "자동참여 실패(call_id=$callId): $labeled")
        AutoParticipateResultSender.send(callId, success = false, errorMessage = labeled, dryRun = dryRun)
    }

    private suspend fun waitForPackageForeground(packageName: String): Boolean {
        var elapsed = 0L
        while (elapsed < STEP_TIMEOUT_MS) {
            if (rootInActiveWindow?.packageName == packageName) return true
            delay(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS
        }
        return rootInActiveWindow?.packageName == packageName
    }

    private suspend fun waitForNodeByText(text: String): AccessibilityNodeInfo? {
        var elapsed = 0L
        while (elapsed < STEP_TIMEOUT_MS) {
            val root = rootInActiveWindow
            if (root != null) {
                val matches = root.findAccessibilityNodeInfosByText(text)
                if (matches.isNotEmpty()) return matches[0]
            }
            delay(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS
        }
        return null
    }

    // 목록의 "첫 신규 항목"을 열기 위한 최선의 추정 - 정확한 뷰 구조를 이 환경에서 확인할 수
    // 없어, 화면에서 클릭 가능한 노드 중 지정된 텍스트(탭 이름 등)를 제외한 첫 번째 노드를
    // 연다. 드라이런 로그로 실제 몇 번째 항목이 열리는지 확인 후 필요하면 조정할 것.
    private suspend fun waitForFirstClickableExcluding(excludeTexts: Set<String>): AccessibilityNodeInfo? {
        var elapsed = 0L
        while (elapsed < STEP_TIMEOUT_MS) {
            val root = rootInActiveWindow
            if (root != null) {
                val found = findFirstClickable(root, excludeTexts)
                if (found != null) return found
            }
            delay(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS
        }
        return null
    }

    private fun findFirstClickable(node: AccessibilityNodeInfo, excludeTexts: Set<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString()
        if (node.isClickable && (text == null || text !in excludeTexts)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstClickable(child, excludeTexts)
            if (result != null) return result
        }
        return null
    }

    private fun clickSelfOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }
}
