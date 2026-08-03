package com.mgad.kakaoalertbridge

import android.app.Notification
import android.provider.Settings
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KakaoNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "CallNotify"
        private const val PKG_GANPOOM_PARTNER = "com.classy.ganpoompartner"
        private const val PKG_GANPAN_STORE = "com.adone.ganpan"
        private const val PKG_KAKAOTALK = "com.kakao.talk"
        private val KAKAO_CHANNEL_KEYWORDS = listOf("간판의품격", "간판스토어")
        // [임시] 이 3개 패키지에서 온 알림은 필터링 없이 전부 디버그 로그로 서버에 남김.
        // "상세 알림이 안드로이드에서 유실되는지" 진단용 - 원인 파악 끝나면 제거할 것.
        private val DEBUG_LOG_PACKAGES = setOf(PKG_GANPOOM_PARTNER, PKG_GANPAN_STORE, PKG_KAKAOTALK)
        private const val SERVER_URL = "https://app.mgad.kr/api/calls/receive"
        private const val DEBUG_LOG_URL = "https://app.mgad.kr/api/calls/debug-notification"
        private const val HEARTBEAT_URL = "https://app.mgad.kr/api/calls/heartbeat"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
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

        // [임시] 리치/커스텀 뷰(RemoteViews) 알림 진단용 - extras에 있는 모든 키와 스타일/커스텀뷰
        // 존재 여부를 확인해서, 카카오톡 알림톡 상세 내용이 표준 텍스트 필드가 아니라 그림으로
        // 그려지는 커스텀 뷰로만 존재하는지 판별.
        val extrasKeys = try { extras.keySet().sorted().joinToString(",") } catch (e: Exception) { "" }
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val hasContentView = sbn.notification.contentView != null
        val hasBigContentView = sbn.notification.bigContentView != null

        if (pkg in DEBUG_LOG_PACKAGES) {
            sendDebugNotification(
                sbn, pkg, title, text, bigText, messagingText, textLines, effectiveBody,
                extrasKeys, template, hasContentView, hasBigContentView
            )
        }

        val fullMessage = if (title.isNotBlank() && title !in KAKAO_CHANNEL_KEYWORDS) {
            "$title\n$effectiveBody"
        } else {
            effectiveBody
        }

        // 간판의품격/간판스토어 자체 앱(com.classy.ganpoompartner/com.adone.ganpan) 알림은 구조화된
        // 정보가 없는 요약 푸시일 뿐이고, 실제 상세 정보는 카카오톡 알림톡으로 온다. 두 경로를 모두
        // 콜로 등록하면 같은 건이 중복 생성되므로, 콜 생성은 카카오톡 경유만 처리한다.
        // (자체 앱 알림도 DEBUG_LOG_PACKAGES에는 남아있어 진단 로그에는 계속 기록됨)
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

    private fun sendDebugNotification(
        sbn: StatusBarNotification,
        pkg: String,
        title: String,
        text: String,
        bigText: String,
        messagingText: String,
        textLines: String,
        effectiveBody: String,
        extrasKeys: String,
        template: String,
        hasContentView: Boolean,
        hasBigContentView: Boolean
    ) {
        scope.launch {
            try {
                val url = URL(DEBUG_LOG_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val payload = JSONObject().apply {
                    put("package", pkg)
                    put("id", sbn.id)
                    put("tag", sbn.tag)
                    put("key", sbn.key)
                    put("group_key", sbn.groupKey)
                    put("title", title)
                    put("text", text)
                    put("big_text", bigText)
                    put("messaging_text", messagingText)
                    put("text_lines", textLines)
                    put("effective_body", effectiveBody)
                    put("extras_keys", extrasKeys)
                    put("template", template)
                    put("has_content_view", hasContentView)
                    put("has_big_content_view", hasBigContentView)
                    put("posted_at", sbn.postTime)
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "디버그 로그 전송 실패", e)
            }
        }
    }

    private fun sendToServer(source: String, message: String, postTime: Long) {
        scope.launch {
            try {
                val url = URL(SERVER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
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
                Log.d(TAG, "서버 전송 결과: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "서버 전송 실패", e)
            }
        }
    }

    // 진단 패널용 하트비트. 알림 리스너가 붙어있는 동안(=서비스가 살아있는 동안) 5분마다 전송.
    // NotificationListenerService는 알림 접근 권한이 켜져있는 한 시스템이 계속 살려서 재바인딩해주므로
    // 별도 WorkManager 없이도 배터리 최적화에 비교적 안정적으로 버틴다.
    private fun sendHeartbeat(listenerConnected: Boolean) {
        scope.launch {
            try {
                val url = URL(HEARTBEAT_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("listener_connected", listenerConnected)
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                Log.d(TAG, "하트비트 전송 결과: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "하트비트 전송 실패", e)
            }
        }
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
