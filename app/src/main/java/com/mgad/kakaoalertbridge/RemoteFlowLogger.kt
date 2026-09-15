package com.mgad.kakaoalertbridge

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 2026-09-16: 계측 로그를 실기기에서 뽑아낼 방법(USB/adb)이 없어, 화면깨우기 수정 설치
// 후에도 재발했을 때 로그로 원인을 확정하지 못하는 게 병목이었다. logcat용 Log.d/w/e 호출을
// 그대로 유지하면서, 같은 내용을 한 곳에 모아뒀다가 플로우가 끝나면(성공/실패 무관)
// RemoteLogSender로 통째로 서버에 올려 관리자 화면에서 바로 볼 수 있게 한다.
//
// PartnersAutoParticipateService와 WakeAndLaunchActivity가 같은 프로세스 안에서 공유하는
// 단일 버퍼 - 접근성 서비스가 큐를 순차 처리(한 번에 한 콜만 진행)하므로 start()~snapshot()
// 사이에 다른 플로우의 로그가 섞여 들어올 일은 없다.
object RemoteFlowLogger {
    private val buffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)

    @Synchronized
    fun start() {
        buffer.setLength(0)
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg, null)
    }

    @Synchronized
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        append("W", tag, msg, tr)
    }

    @Synchronized
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        append("E", tag, msg, tr)
    }

    private fun append(level: String, tag: String, msg: String, tr: Throwable?) {
        buffer.append(timeFormat.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
        if (tr != null) {
            buffer.append("    ").append(Log.getStackTraceString(tr)).append('\n')
        }
    }

    @Synchronized
    fun snapshot(): String = buffer.toString()
}
