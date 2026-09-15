package com.mgad.kakaoalertbridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
        // 2026-09-15: 콜 684 실제 실패 로그로 8초가 부족한 사례를 확인함([2/8단계] 파트너스 앱
        // 전면 전환 대기 시간 초과 - queries 수정으로 앱 실행 자체는 성공했는데 그 다음 이
        // 단계에서 막힘). 오래 안 쓴 앱은 콜드 스타트가 느릴 수 있어 15초로 상향 - 이 상수를
        // waitForNodeByText/waitForFirstClickableExcluding도 함께 쓰므로 전체 단계가 고르게
        // 여유를 갖는다.
        private const val STEP_TIMEOUT_MS = 15000L
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
        // processNextIfIdle은 suspend가 아닌 일반 함수(알림 콜백 체인에서 바로 호출됨)라
        // AutoParticipateResultSender.send()(suspend)를 부르려면 별도 스코프가 필요하다.
        private val companionScope = CoroutineScope(Dispatchers.IO)

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
                companionScope.launch {
                    AutoParticipateResultSender.send(nextId, success = false, errorMessage = "접근성 서비스가 켜져있지 않음", dryRun = dryRun)
                }
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
            // 2026-09-16: 이 플로우(계측 대상: 여기부터 finally에서 RemoteLogSender.send()까지)
            // 동안의 모든 RemoteFlowLogger.d/w/e 호출을 한 세션으로 모은다 - 큐가 한 번에
            // 한 콜만 처리하므로 start()~snapshot() 사이 다른 플로우 로그가 섞일 일은 없다.
            RemoteFlowLogger.start()
            var succeeded = false
            val packageName = AutoParticipateSettings.getPartnersPackageName(applicationContext)
            val tabText = AutoParticipateSettings.getTabButtonText(applicationContext)
            val participateText = AutoParticipateSettings.getParticipateButtonText(applicationContext)

            // 2026-09-15: 실패 알림에 "몇 번째 단계"인지가 안 남아 있어서, com.classy.ganpoompartner
            // 패키지명 확인 등 엉뚱한 곳을 의심하며 시간을 썼던 문제 - 모든 실패 사유 앞에 단계
            // 번호를 붙여, 다음에 또 실패해도 관리자 알림 문구만 보고 바로 어느 단계인지 알 수 있게 한다.
            val totalSteps = 8

            // 2026-09-15: 콜 684/692 재조사 - 타임아웃을 8초->15초로 올렸는데도 다음날 아침
            // 똑같이 [2/8단계]에서 실패, 게다가 트리거 시각과 실패 보고 시각 사이 실제
            // 간격이 15초가 아니라 4분 가까이 벌어져 있었다(화면이 꺼져 CPU가 잠들면
            // delay() 기반 폴링 루프의 "경과 시간" 계산이 실제 경과 시간과 어긋남). 화면이
            // 꺼진/잠긴 채로는 파트너스 앱 액티비티가 태스크 스택에만 올라가고 실제로 화면
            // 맨 위(rootInActiveWindow)는 계속 잠금화면 패키지로 남아 아무리 기다려도 통과할
            // 수 없었던 것 - 타임아웃 값의 문제가 아니었다. 플로우 시작부터 끝까지 파셜
            // 웨이크락으로 CPU가 안 잠들게 유지한다.
            val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:participate:$callId")
            wakeLock.acquire(STEP_TIMEOUT_MS * totalSteps + 30000L)
            // 2026-09-16: 화면깨우기 수정(WakeAndLaunchActivity, commit 4acb60c) 설치 후에도
            // 11:33 알림에서 동일 [2/8단계] 오류 재발 - 이 수정이 실제로 의도대로 동작하는지
            // 자체가 검증 안 된 채로 "고쳤다"고 보고해온 게 문제였다. 원인을 또 추측해서
            // 고치는 대신, 아래 flowStartElapsedMs를 기준 시계로 삼아 서비스<->액티비티 양쪽
            // 로그를 하나로 이어 붙일 수 있게 하고, 2단계 대기 중 매 폴링마다 실제로 감지된
            // 패키지명을 전부 남겨 다음 실패 시 "액티비티가 아예 안 불렸는지 / 불렸는데 API
            // 호출이 실패했는지 / 다 성공했는데 화면엔 여전히 다른 게 떠있는지"를 로그만으로
            // 확정한다. 원인이 로그로 확정되기 전까지 이 블록에 추측성 수정을 추가하지 않는다.
            val flowStartElapsedMs = SystemClock.elapsedRealtime()
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                RemoteFlowLogger.d(TAG, "call_id=$callId 시작 - 패키지=$packageName, 시작 시점 isInteractive=${powerManager.isInteractive}")

                // 대상 앱이 아예 없으면 화면을 깨울 필요도 없이 바로 실패 처리.
                if (packageManager.getLaunchIntentForPackage(packageName) == null) {
                    fail(callId, dryRun, 1, totalSteps, "파트너스 앱(${packageName})을 찾을 수 없음")
                    return@launch
                }
                // WakeAndLaunchActivity가 화면을 켜고(꺼져 있었다면) 잠금을 넘긴 뒤에야
                // 파트너스 앱을 실행한다 - 접근성 서비스 자체는 윈도우가 없어 이 두 가지를
                // 직접 할 수 없음.
                val wakeIntent = Intent(applicationContext, WakeAndLaunchActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(WakeAndLaunchActivity.EXTRA_TARGET_PACKAGE, packageName)
                    putExtra(WakeAndLaunchActivity.EXTRA_CALL_ID, callId)
                    putExtra(WakeAndLaunchActivity.EXTRA_TRIGGER_ELAPSED_MS, flowStartElapsedMs)
                }
                try {
                    startActivity(wakeIntent)
                    RemoteFlowLogger.d(TAG, "call_id=$callId WakeAndLaunchActivity startActivity 호출 완료(경과=${SystemClock.elapsedRealtime() - flowStartElapsedMs}ms)")
                } catch (t: Throwable) {
                    RemoteFlowLogger.e(TAG, "call_id=$callId WakeAndLaunchActivity startActivity 중 예외", t)
                }

                if (!waitForPackageForeground(callId, flowStartElapsedMs, packageName)) {
                    RemoteFlowLogger.w(TAG, "call_id=$callId 2단계 최종 실패 - 총 경과=${SystemClock.elapsedRealtime() - flowStartElapsedMs}ms, 종료 시점 isInteractive=${powerManager.isInteractive}, 최종 rootInActiveWindow.packageName=${rootInActiveWindow?.packageName}")
                    fail(callId, dryRun, 2, totalSteps, "파트너스 앱 전면 전환 대기 시간 초과")
                    return@launch
                }
                RemoteFlowLogger.d(TAG, "call_id=$callId 2단계 통과 - 경과=${SystemClock.elapsedRealtime() - flowStartElapsedMs}ms")

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
                    RemoteFlowLogger.d(TAG, "[드라이런] call_id=$callId \"$participateText\" 버튼 발견(8/$totalSteps 단계 진입 성공) - 실제 클릭 안 함")
                    succeeded = true
                    AutoParticipateResultSender.send(callId, success = true, errorMessage = null, dryRun = true)
                } else {
                    if (!clickSelfOrClickableAncestor(participateNode)) {
                        fail(callId, dryRun, 8, totalSteps, "\"$participateText\" 버튼 클릭 실패(클릭 가능한 노드 없음)")
                        return@launch
                    }
                    RemoteFlowLogger.d(TAG, "call_id=$callId \"$participateText\" 클릭 완료(8/$totalSteps 단계 전부 성공)")
                    succeeded = true
                    AutoParticipateResultSender.send(callId, success = true, errorMessage = null, dryRun = false)
                }
            } catch (e: Exception) {
                RemoteFlowLogger.e(TAG, "자동참여 흐름 중 예외(call_id=$callId)", e)
                AutoParticipateResultSender.send(callId, success = false, errorMessage = "예외: ${e.message}", dryRun = dryRun)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                // 2026-09-16: 계측 로그를 실기기에서 뽑아낼 방법이 없어 원인 확정이 막혔던
                // 문제 - 플로우가 끝나면(성공/실패 무관) 여기까지 쌓인 로그를 통째로 서버에
                // 올려 관리자 화면(설정 페이지)에서 USB 없이 바로 확인할 수 있게 한다.
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                RemoteLogSender.send(callId, deviceId, succeeded, RemoteFlowLogger.snapshot())
                performGlobalAction(GLOBAL_ACTION_HOME)
                onComplete()
            }
        }
    }

    // 2026-09-15: send()가 suspend로 바뀌면서 fail()도 suspend가 됨 - 호출부(runParticipateFlow의
    // try 블록)가 전부 코루틴 안이라 그대로 호출 가능. 이제 fail()이 리턴해야 send()가 실제로
    // 끝난(또는 재시도까지 소진한) 것이므로, finally의 performGlobalAction/onComplete가 보고
    // 완료 전에 먼저 실행되는 일이 없다.
    private suspend fun fail(callId: Int, dryRun: Boolean, step: Int, totalSteps: Int, reason: String) {
        val labeled = "[$step/${totalSteps}단계] $reason"
        RemoteFlowLogger.w(TAG, "자동참여 실패(call_id=$callId): $labeled")
        AutoParticipateResultSender.send(callId, success = false, errorMessage = labeled, dryRun = dryRun)
    }

    // 2026-09-15: elapsed를 POLL_INTERVAL_MS 누적(카운터)으로 계산하던 방식은 delay() 호출이
    // 실제로 그 시간만큼만 걸린다는 전제에 의존한다. 화면이 꺼져 기기가 Doze/절전 상태로
    // 들어가면 delay()가 실제로는 훨씬 오래(수십 초~수 분) 걸릴 수 있는데도 카운터는 여전히
    // "타임아웃 값만큼만 기다렸다"고 착각해 정해진 반복 횟수를 다 채울 때까지 계속 대기하게
    // 된다(콜 684/692 - 트리거~실패 보고 간 실제 간격이 15초가 아니라 4분 가까이 벌어졌던
    // 원인). SystemClock.elapsedRealtime()(절전 중에도 흐르는 단조 시계)으로 실제 경과 시간을
    // 재도록 바꿔, 타임아웃이 실제 벽시계 기준으로도 의도한 값을 넘지 않게 한다.
    // 2026-09-16: 매 폴링마다 실제로 감지된 패키지명을 남긴다 - null(rootInActiveWindow 자체가
    // 없음)인지, 잠금화면/런처 등 다른 패키지인지, 그냥 감지 로직 버그로 대상 패키지인데도
    // 놓치는 것인지 이 로그만으로 구분하기 위함.
    private suspend fun waitForPackageForeground(callId: Int, flowStartElapsedMs: Long, packageName: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STEP_TIMEOUT_MS
        var pollCount = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val observed = rootInActiveWindow?.packageName
            pollCount++
            RemoteFlowLogger.d(TAG, "call_id=$callId 2단계 폴링 #$pollCount(경과=${SystemClock.elapsedRealtime() - flowStartElapsedMs}ms): rootInActiveWindow.packageName=$observed")
            if (observed == packageName) return true
            delay(POLL_INTERVAL_MS)
        }
        return rootInActiveWindow?.packageName == packageName
    }

    private suspend fun waitForNodeByText(text: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + STEP_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val matches = root.findAccessibilityNodeInfosByText(text)
                if (matches.isNotEmpty()) return matches[0]
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    // 목록의 "첫 신규 항목"을 열기 위한 최선의 추정 - 정확한 뷰 구조를 이 환경에서 확인할 수
    // 없어, 화면에서 클릭 가능한 노드 중 지정된 텍스트(탭 이름 등)를 제외한 첫 번째 노드를
    // 연다. 드라이런 로그로 실제 몇 번째 항목이 열리는지 확인 후 필요하면 조정할 것.
    private suspend fun waitForFirstClickableExcluding(excludeTexts: Set<String>): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + STEP_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val found = findFirstClickable(root, excludeTexts)
                if (found != null) return found
            }
            delay(POLL_INTERVAL_MS)
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
