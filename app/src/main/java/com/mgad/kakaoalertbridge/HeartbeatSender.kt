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

        // 2026-09-14: 주말 자동참여 전수조사에서, 트리거 자체가 로컬 설정(SharedPreferences)
        // 게이트에 막혀 서버에 아무 흔적도 안 남기고 조용히 종료되는 사고가 확인됨. 이 설정은
        // 폰 안에서만 존재하고 서버/관리자 화면에서는 전혀 보이지 않았던 게 원인 - 재발 방지를
        // 위해 하트비트에 실어 서버가 "기능이 꺼진 채로 방치됐는지"를 직접 감시할 수 있게 한다.
        val autoParticipateEnabled = AutoParticipateSettings.isEnabled(appContext)
        val autoParticipateBusinessHoursEnabled = AutoParticipateSettings.isBusinessHoursEnabled(appContext)
        val autoParticipateDryRun = AutoParticipateSettings.isDryRun(appContext)
        val autoParticipateConfigured = AutoParticipateSettings.isConfigured(appContext)
        val enabledAccessibilityServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val accessibilityServiceEnabled = enabledAccessibilityServices.contains(
            "${appContext.packageName}/${appContext.packageName}.PartnersAutoParticipateService"
        )

        // FCM 토큰 조회는 비동기(Task)라 콜백 안에서 실제 전송을 수행한다. 토큰 조회에
        // 실패해도(Firebase 미연동/네트워크 문제 등) 하트비트 자체는 보내야 하므로
        // 실패 시 null로 두고 계속 진행 - 서버는 fcm_token이 없으면 웨이크 핑 대상에서
        // 제외할 뿐 하트비트 자체 처리에는 지장이 없다.
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                val fcmToken = if (task.isSuccessful) task.result else null
                sendPayload(
                    deviceId, listenerConnected, batteryOptimizationIgnored, fcmToken,
                    autoParticipateEnabled, autoParticipateBusinessHoursEnabled,
                    autoParticipateDryRun, autoParticipateConfigured, accessibilityServiceEnabled,
                )
            }
    }

    private fun sendPayload(
        deviceId: String,
        listenerConnected: Boolean,
        batteryOptimizationIgnored: Boolean,
        fcmToken: String?,
        autoParticipateEnabled: Boolean,
        autoParticipateBusinessHoursEnabled: Boolean,
        autoParticipateDryRun: Boolean,
        autoParticipateConfigured: Boolean,
        accessibilityServiceEnabled: Boolean,
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
                    put("auto_participate_enabled", autoParticipateEnabled)
                    put("auto_participate_business_hours_enabled", autoParticipateBusinessHoursEnabled)
                    put("auto_participate_dry_run", autoParticipateDryRun)
                    put("auto_participate_configured", autoParticipateConfigured)
                    put("accessibility_service_enabled", accessibilityServiceEnabled)
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
