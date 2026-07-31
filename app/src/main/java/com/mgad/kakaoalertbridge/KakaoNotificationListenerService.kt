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
        private val TARGET_PACKAGES = setOf(PKG_GANPOOM_PARTNER, PKG_GANPAN_STORE)
        private const val SERVER_URL = "https://app.mgad.kr/api/calls/receive"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        if (sbn.packageName !in TARGET_PACKAGES) return

        val source = when (sbn.packageName) {
            PKG_GANPOOM_PARTNER -> "간판의품격"
            PKG_GANPAN_STORE -> "간판스토어"
            else -> sbn.packageName
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        val fullMessage = if (title.isNotBlank()) "$title\n$bigText" else bigText

        Log.d(TAG, "[$source] 알림 수신 - $fullMessage")

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
