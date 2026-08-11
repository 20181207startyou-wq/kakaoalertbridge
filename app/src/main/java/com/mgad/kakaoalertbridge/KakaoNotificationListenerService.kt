package com.mgad.kakaoalertbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
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

        private const val FOREGROUND_CHANNEL_ID = "bridge_running"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
    }

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

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
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
    // 2026-08-11: 배터리 최적화 예외 상태를 함께 실어 보내, 원격(웹 진단 패널)에서도 예외가
    // 다시 꺼졌는지(OS 업데이트나 사용자 실수로 재설정되는 경우 등) 확인할 수 있게 한다.
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
                    put("battery_optimization_ignored", isIgnoringBatteryOptimizations())
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
