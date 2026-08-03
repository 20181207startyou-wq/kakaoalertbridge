package com.mgad.kakaoalertbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        private val TARGET_APP_PACKAGES = setOf(PKG_GANPOOM_PARTNER, PKG_GANPAN_STORE)
        private val KAKAO_CHANNEL_KEYWORDS = listOf("간판의품격", "간판스토어")
        private const val SERVER_URL = "https://app.mgad.kr/api/calls/receive"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        val fullMessage = if (title.isNotBlank() && title !in KAKAO_CHANNEL_KEYWORDS) {
            "$title\n$bigText"
        } else {
            bigText
        }

        val source: String = when {
            pkg == PKG_GANPOOM_PARTNER -> "간판의품격"
            pkg == PKG_GANPAN_STORE -> "간판스토어"
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "알림 리스너 연결됨")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "알림 리스너 연결 끊김")
    }
}
