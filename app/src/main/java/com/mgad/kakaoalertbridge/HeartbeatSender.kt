package com.mgad.kakaoalertbridge

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 하트비트 전송 로직을 KakaoNotificationListenerService(5분 주기 루프)와
// FcmMessagingService(서버 웨이크 핑 수신 시 즉시 전송)가 공유하기 위해 분리.
object HeartbeatSender {
    private const val TAG = "HeartbeatSender"
    private const val HEARTBEAT_URL = "https://app.mgad.kr/api/calls/heartbeat"
    // backend/.env의 BRIDGE_SECRET_KEY와 동일해야 함.
    private const val BRIDGE_SECRET = "31b0cce65959eafe3af0fe13de8ea986e2a6bbdc12e66f71"

    private val scope = CoroutineScope(Dispatchers.IO)

    fun send(context: Context, listenerConnected: Boolean) {
        val appContext = context.applicationContext
        val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(appContext.packageName)

        // FCM 토큰 조회는 비동기(Task)라 콜백 안에서 실제 전송을 수행한다. 토큰 조회에
        // 실패해도(Firebase 미연동/네트워크 문제 등) 하트비트 자체는 보내야 하므로
        // 실패 시 null로 두고 계속 진행 - 서버는 fcm_token이 없으면 웨이크 핑 대상에서
        // 제외할 뿐 하트비트 자체 처리에는 지장이 없다.
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                val fcmToken = if (task.isSuccessful) task.result else null
                sendPayload(deviceId, listenerConnected, batteryOptimizationIgnored, fcmToken)
            }
    }

    private fun sendPayload(
        deviceId: String,
        listenerConnected: Boolean,
        batteryOptimizationIgnored: Boolean,
        fcmToken: String?,
    ) {
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
                    put("battery_optimization_ignored", batteryOptimizationIgnored)
                    if (fcmToken != null) put("fcm_token", fcmToken)
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
}
