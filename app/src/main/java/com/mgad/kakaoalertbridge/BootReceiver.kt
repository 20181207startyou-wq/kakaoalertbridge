package com.mgad.kakaoalertbridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

// 2026-11-18(item 1): 재부팅/앱 교체(업데이트) 후 알림 리스너가 스스로 다시 붙게 요청한다.
// 리스너가 rebind되면 onListenerConnected에서 하트비트 루프가 다시 시작돼 복구된다
// (11/10 아침 앱 종료로 40분간 하트비트가 끊긴 사례 완화). 알림 접근 권한이 유지돼 있을 때만
// 시스템이 재바인드하므로, 권한이 꺼져 있으면 아무 일도 하지 않는다(안전).
//
// 참고: "재부팅 없는 프로세스 강제종료" 복구까지 완전히 보장하려면 WorkManager 주기 점검이
// 필요하다(현재 버전 카탈로그에 미포함 - 별도 작업으로 분리). 리스너 포그라운드 서비스 +
// onListenerDisconnected의 requestRebind + 이 BootReceiver로 재부팅/끊김 경로는 커버한다.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "수신: $action - 알림 리스너 rebind 요청")
        try {
            val component = ComponentName(context, KakaoNotificationListenerService::class.java)
            NotificationListenerService.requestRebind(component)
        } catch (t: Throwable) {
            Log.e(TAG, "requestRebind 실패", t)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
