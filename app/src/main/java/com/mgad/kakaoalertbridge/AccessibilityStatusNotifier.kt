package com.mgad.kakaoalertbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

// 2026-10-15 사고 재발 방지: 자동참여 접근성 서비스가 꺼지면(사용자가 끈 것/제조사 절전·
// 미사용 앱 권한 자동해제/시스템이 서비스 비정상 종료 후 꺼버림 등) 트리거가 떠도 자동참여
// 플로우 자체가 실행되지 않고, 앱은 계속 하트비트를 보내 "온라인"이라 서버 응답없음 알림도
// 울리지 않는다. 접근성 서비스는 앱이 스스로 다시 켤 수 없으므로(보안상 Settings 화면에서
// 사용자가 직접 켜야 함), 꺼진 걸 감지하면 "눌러서 접근성 설정으로 바로 가는" 상시 알림을
// 띄워 즉시 복구를 유도한다.
//
// 알림은 서버 하트비트 경로와 별개의 "폰에 직접 보이는" 2차 안전장치다. 하트비트 기반 서버
// 알림은 관리자에게 가지만, 이 상시 알림은 회사폰을 들고 있는 사람 눈에 바로 보여 가장 빠른
// 복구 경로가 된다.
object AccessibilityStatusNotifier {
    private const val TAG = "AccessibilityNotifier"
    private const val CHANNEL_ID = "accessibility_off"
    private const val NOTIFICATION_ID = 1002

    // HeartbeatSender / MainActivity와 동일한 판정 로직. 세 곳에서 각자 Settings.Secure를
    // 읽던 것을 한 군데로 모아, 판정 기준(서비스 컴포넌트명)이 어긋나지 않게 한다.
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val pkg = context.applicationContext.packageName
        return enabled.contains("$pkg/$pkg.PartnersAutoParticipateService")
    }

    // 접근성 서비스가 꺼져 있으면 상시 알림을 띄우고, 켜져 있으면 알림을 내린다.
    // 앱 실행 시(MainActivity.onResume), 주기(하트비트 5분 루프), 리스너 재연결(onListenerConnected,
    // = 업데이트 설치 후 프로세스 재시작/첫 실행 포함)마다 호출된다 - 멱등이라 여러 번 불려도
    // 같은 ID의 알림을 갱신/취소할 뿐 쌓이지 않는다.
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (isServiceEnabled(appContext)) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        try {
            showDisabledNotification(appContext, nm)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS 권한 거부 등 - 알림만 못 띄울 뿐 다른 동작을 막지 않는다.
            Log.e(TAG, "접근성 꺼짐 알림 표시 실패", e)
        }
    }

    private fun showDisabledNotification(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "자동참여 접근성 꺼짐 경고",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "자동참여 접근성 서비스가 꺼져 콜을 자동참여하지 못하는 상태임을 알립니다."
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }

        // 탭하면 접근성 설정 화면으로 바로 이동 - 거기서 "MG애드 야간 상담참여 자동확보"를
        // 다시 켜면 된다. (앱이 코드로 켤 수는 없다.)
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context, 0, settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("⚠️ 자동참여가 멈췄습니다")
            .setContentText("접근성 서비스가 꺼져 콜 자동참여가 안 됩니다. 눌러서 다시 켜주세요.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "접근성 서비스가 꺼져 있어 간판의품격 콜이 자동참여되지 않습니다.\n" +
                        "눌러서 접근성 설정 → 'MG애드 야간 상담참여 자동확보'를 다시 켜주세요.\n" +
                        "(토글이 회색으로 안 켜지면: 앱 정보 → 우측 상단 점 세 개 → '제한된 설정 허용' 후 다시 시도)"
                )
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pending)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
