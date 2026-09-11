package com.mgad.kakaoalertbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KakaoNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "CallNotify"
        private const val PKG_KAKAOTALK = "com.kakao.talk"
        // 업무시간 외 "상담참여" 자동확보 대상은 간판의품격뿐 - 간판스토어는 파트너스 앱
        // 자동참여 버튼 자체가 없으므로 절대 트리거되면 안 됨(maybeTriggerAutoParticipate 참고).
        private const val GANPAN_QUALITY_SOURCE = "간판의품격"
        private val KAKAO_CHANNEL_KEYWORDS = listOf(GANPAN_QUALITY_SOURCE, "간판스토어")
        private const val SERVER_URL = "https://app.mgad.kr/api/calls/receive"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        // 서버의 /calls/receive, /calls/heartbeat 스팸성 데이터 주입 방지용 시크릿.
        // 서버는 소프트 롤아웃 중이라 이 헤더가 없어도 일단 통과되지만(2026-08-07 기준
        // 구버전 앱 호환), 값이 있는데 틀리면 거부한다. backend/.env의 BRIDGE_SECRET_KEY와 동일해야 함.
        private const val BRIDGE_SECRET = "31b0cce65959eafe3af0fe13de8ea986e2a6bbdc12e66f71"

        private const val FOREGROUND_CHANNEL_ID = "bridge_running"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null

    // 2026-08-11: 하트비트가 일반 코루틴 루프뿐이라 백그라운드에서 프로세스가 통째로 죽으면
    // 5분 주기가 전혀 지켜지지 않고, 시스템이 알림 리스너를 다시 바인딩해줄 때까지(불규칙,
    // 최대 4시간+ 관측됨) 하트비트가 끊기는 문제가 있었음. 포그라운드 서비스로 승격해
    // 프로세스 우선순위를 높여 백그라운드 킬 가능성을 낮춘다.
    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "콜 알림 감지 실행 상태",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "MG애드 콜 알림 브릿지가 백그라운드에서 정상 실행 중임을 표시합니다."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("MG애드 콜 알림 감지 중")
            .setContentText("백그라운드에서 정상 동작 중입니다.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()

        try {
            when {
                Build.VERSION.SDK_INT >= 34 ->
                    startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ->
                    startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // POST_NOTIFICATIONS 권한이 거부된 경우 등 - 알림만 안 보일 뿐 서비스 자체는
            // 계속 동작해야 하므로 하트비트/알림 감지 로직을 막지 않고 로그만 남긴다.
            Log.e(TAG, "포그라운드 알림 시작 실패", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text

        // 카카오톡 알림톡이 대화형(MessagingStyle) 알림으로 오면 실제 본문이 EXTRA_TEXT/EXTRA_BIG_TEXT가
        // 아니라 EXTRA_MESSAGES(대화 메시지 목록) 또는 EXTRA_TEXT_LINES에 들어있을 수 있음.
        // 여러 후보 중 가장 긴(=정보가 가장 많을) 것을 실제 본문으로 사용.
        val messagingText = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
                ?.messages
                ?.mapNotNull { it.text?.toString() }
                ?.joinToString("\n")
                ?: ""
        } catch (e: Exception) {
            ""
        }
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() } ?: ""
        val effectiveBody = listOf(bigText, messagingText, textLines).maxByOrNull { it.length }
            ?.takeIf { it.isNotBlank() } ?: bigText

        val fullMessage = if (title.isNotBlank() && title !in KAKAO_CHANNEL_KEYWORDS) {
            "$title\n$effectiveBody"
        } else {
            effectiveBody
        }

        // 간판의품격/간판스토어 자체 앱(com.classy.ganpoompartner/com.adone.ganpan) 알림은 구조화된
        // 정보가 없는 요약 푸시일 뿐이고, 실제 상세 정보는 카카오톡 알림톡으로 온다. 두 경로를 모두
        // 콜로 등록하면 같은 건이 중복 생성되므로, 콜 생성은 카카오톡 경유만 처리한다.
        val source: String = when {
            pkg == PKG_KAKAOTALK -> {
                val matched = KAKAO_CHANNEL_KEYWORDS.firstOrNull { title.contains(it) }
                if (matched == null) {
                    return
                }
                matched
            }
            else -> return
        }

        Log.d(TAG, "[$source] (pkg=$pkg) 알림 수신 - $fullMessage")

        sendToServer(source, fullMessage, sbn.postTime)
    }

    private fun sendToServer(source: String, message: String, postTime: Long) {
        scope.launch {
            try {
                val url = URL(SERVER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("X-Bridge-Secret", BRIDGE_SECRET)
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val payload = JSONObject().apply {
                    put("source", source)
                    put("message", message)
                    put("received_at", postTime)
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                val responseStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseBody = responseStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.d(TAG, "서버 전송 결과: $responseCode - $responseBody")
                conn.disconnect()

                maybeTriggerAutoParticipate(source, message, responseCode, responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "서버 전송 실패", e)
            }
        }
    }

    // 간판의품격 상세 알림("상담요청이 도착했어요") 또는 "OOO님께서 사장님의 상담 참여를
    // 기다리고 계세요!" 대기 리마인더가 도착하면, 서버 응답에 실린 call_ids를 들고 접근성
    // 서비스를 트리거해 파트너스 앱의 "상담참여"를 자동으로 확보한다.
    //
    // 2026-09-11: 실제 사고(김정은님 건, 마감 09/14)로 리마인더 알림은 이 트리거가 전혀
    // 시도조차 안 되고 있었음이 확인됨 - "상담요청이 도착했어요" 문구만 검사해서, 상세
    // 알림 없이 리마인더만 오는(또는 카카오톡이 여러 건을 한 알림에 이어붙여 보내는) 경우를
    // 완전히 놓치고 있었다. 백엔드(process_kakao_message)는 이미 이 리마인더를 콜로 만들고
    // 있었으므로 트리거 조건만 넓히면 된다.
    //
    // 체크 순서(최우선 순위부터):
    // 1) 자동참여 제외 시간대(auto_participate_excluded) - 관리자가 설정한 요일+시간 구간
    //    (예: 금요일 18:00~토요일 24:00)에 해당하면 업무시간 중/외 토글과 무관하게 무조건
    //    스킵한다. 서버(is_within_exclusion_window)가 계산해 응답에 실어주므로 클라이언트가
    //    직접 시간대 목록을 들고 있을 필요가 없다 - 콜 자체는 평소처럼 생성/보류되고 이
    //    트리거만 건너뛴다(의도된 동작, 응대해봤자 이미 늦은 콜이라 자동참여도 포기).
    // 2) 업무시간 외에는 항상 시도(AutoParticipateSettings.isEnabled 토글). 업무시간 중에는
    //    별도 토글(AutoParticipateSettings.isBusinessHoursEnabled, 기본 꺼짐)이 켜져 있을 때만
    //    시도한다 - 켜져 있어도 그 이후 처리는 서버의 is_business_hours() 조기 리턴 덕에 평소
    //    업무시간 콜과 동일하게(hold 없이) 흘러가므로 여기서 추가로 분기할 필요는 없다.
    // 업무시간 계산은 백엔드 is_business_hours()(KST 월~금 09~18시)와 동일하게 맞춰야
    // "업무시간인데 자동참여를 시도"하거나 "업무시간 외인데 자동참여를 안 하는" 불일치가 없다.
    //
    // source(온 알림의 title 매칭 키워드, "간판의품격"/"간판스토어")로 먼저 플랫폼을 하드
    // 게이트한다 - 메시지 본문에 트리거 문구가 우연히 들어있어도 source가 간판의품격이
    // 아니면 절대 트리거되지 않음. 당근비즈/숨고/크몽은 애초에 KAKAO_CHANNEL_KEYWORDS에
    // 없어 이 함수까지 오지도 않는다(onNotificationPosted 참고).
    private fun maybeTriggerAutoParticipate(source: String, message: String, responseCode: Int, responseBody: String) {
        if (source != GANPAN_QUALITY_SOURCE) return
        if (responseCode !in 200..299) return
        val isDetailNotification = "상담요청이 도착했어요" in message
        // 백엔드 GANPAN_QUALITY_WAITING_PATTERN과 동일한 핵심 문구(이름/느낌표 제외)
        val isWaitingReminder = "사장님의 상담 참여를 기다리고 계세요" in message
        if (!isDetailNotification && !isWaitingReminder) return

        val response = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            null
        }

        if (response?.optBoolean("auto_participate_excluded", false) == true) {
            Log.d(TAG, "자동참여 제외 시간대 - 트리거 스킵(콜 자체는 평소대로 생성/보류됨)")
            return
        }

        val businessHours = isBusinessHoursKst()
        if (businessHours && !AutoParticipateSettings.isBusinessHoursEnabled(applicationContext)) return

        // 카카오톡이 미읽음 알림을 이어붙여 보내면 한 원문에 여러 고객의 리마인더가 섞여
        // 오는 경우가 흔하다(백엔드가 이미 블록 단위로 쪼개 각각 별도 콜을 만들고 call_ids
        // 배열로 전부 돌려줌) - call_id 하나만 보고 나머지를 버리면 첫 번째 고객만 자동참여
        // 되고 나머지는 그대로 남는다(김정은님 건이 바로 이 케이스: 한 알림에 4명이 묶여
        // 왔는데 4번째였음). 구버전 서버 호환을 위해 call_ids가 없으면 call_id 하나로 폴백.
        val callIds = mutableListOf<Int>()
        val callIdsArray: JSONArray? = response?.optJSONArray("call_ids")
        if (callIdsArray != null) {
            for (i in 0 until callIdsArray.length()) {
                val id = callIdsArray.optInt(i, -1)
                if (id > 0) callIds.add(id)
            }
        }
        if (callIds.isEmpty()) {
            val callId = response?.optInt("call_id", -1) ?: -1
            if (callId > 0) callIds.add(callId)
        }
        if (callIds.isEmpty()) {
            Log.w(TAG, "자동참여 트리거 스킵 - 응답에 call_id(s) 없음: $responseBody")
            return
        }
        PartnersAutoParticipateService.trigger(applicationContext, callIds, businessHours)
    }

    private fun isBusinessHoursKst(): Boolean {
        val kst = java.util.TimeZone.getTimeZone("Asia/Seoul")
        val cal = java.util.Calendar.getInstance(kst)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) return false
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 9..17
    }

    // 진단 패널용 하트비트. 알림 리스너가 붙어있는 동안(=서비스가 살아있는 동안) 5분마다 전송.
    // 실제 전송 로직은 HeartbeatSender(FcmMessagingService의 웨이크 핑 핸들러와 공유)로 위임.
    private fun sendHeartbeat(listenerConnected: Boolean) {
        HeartbeatSender.send(this, listenerConnected)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat(listenerConnected = true)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "알림 리스너 연결됨")
        sendHeartbeat(listenerConnected = true)
        startHeartbeatLoop()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "알림 리스너 연결 끊김")
        heartbeatJob?.cancel()
        sendHeartbeat(listenerConnected = false)
    }
}
