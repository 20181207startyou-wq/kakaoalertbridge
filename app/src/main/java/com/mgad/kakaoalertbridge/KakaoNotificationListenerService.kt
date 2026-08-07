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
        private const val PKG_KAKAOTALK = "com.kakao.talk"
        private val KAKAO_CHANNEL_KEYWORDS = listOf("간판의품격", "간판스토어")
        private const val SERVER_URL = "https://app.mgad.kr/api/calls/receive"
        private const val HEARTBEAT_URL = "https://app.mgad.kr/api/calls/heartbeat"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        // 서버의 /calls/receive, /calls/heartbeat 스팸성 데이터 주입 방지용 시크릿.
        // 서버는 소프트 롤아웃 중이라 이 헤더가 없어도 일단 통과되지만(2026-08-07 기준
        // 구버전 앱 호환), 값이 있는데 틀리면 거부한다. backend/.env의 BRIDGE_SECRET_KEY와 동일해야 함.
        private const val BRIDGE_SECRET = "31b0cce65959eafe3af0fe13de8ea986e2a6bbdc12e66f71"
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
                conn.setRequestProperty("X-Bridge-Secret", BRIDGE_SECRET)
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
