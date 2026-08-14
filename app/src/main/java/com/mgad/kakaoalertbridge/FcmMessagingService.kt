package com.mgad.kakaoalertbridge

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// 하트비트 이중 안전장치: 포그라운드 서비스 전환만으로는 일부 기기(삼성 등)의
// 절전관리가 프로세스를 죽이는 걸 완전히 못 막아 응답없음 알림(90분 임계값)이
// 재발했음(2026-08-14). 서버가 하트비트 유실을 더 짧은 주기(35분)로 감지해 보내는
// 무음 data-only 푸시를 받으면, 사용자에게 알림을 띄우지 않고 바로 하트비트를
// 재전송해 프로세스가 살아있음을 서버에 다시 알린다.
class FcmMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmMessagingService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "새 FCM 토큰 발급 - 하트비트에 실어 서버에 전달")
        HeartbeatSender.send(applicationContext, listenerConnected = true)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        if (remoteMessage.data["type"] == "wake_ping") {
            Log.d(TAG, "서버 웨이크 핑 수신 - 하트비트 즉시 재전송")
            HeartbeatSender.send(applicationContext, listenerConnected = true)
        }
    }
}
