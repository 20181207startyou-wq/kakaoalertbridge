package com.mgad.kakaoalertbridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// RemoteFlowLogger가 모아둔 플로우 계측 로그 전문을 서버로 전송. HeartbeatSender/
// AutoParticipateResultSender와 동일한 HttpURLConnection + X-Bridge-Secret 패턴.
object RemoteLogSender {
    private const val TAG = "RemoteLogSender"
    private const val URL_STR = "https://app.mgad.kr/api/calls/auto-participate-flow-log"
    // backend/.env의 BRIDGE_SECRET_KEY와 동일해야 함.
    private const val BRIDGE_SECRET = "31b0cce65959eafe3af0fe13de8ea986e2a6bbdc12e66f71"
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 1500L
    // 서버 MAX_FLOW_LOG_CHARS(calls.py)와 동일 - 초과분은 서버에서도 뒤쪽(최신)만 남기지만,
    // 업로드 자체를 가볍게 하기 위해 클라이언트에서 먼저 자른다.
    private const val MAX_LOG_CHARS = 100_000

    suspend fun send(callId: Int?, deviceId: String?, success: Boolean?, logText: String): Boolean =
        withContext(Dispatchers.IO) {
            val trimmed = if (logText.length > MAX_LOG_CHARS) logText.takeLast(MAX_LOG_CHARS) else logText
            val payload = JSONObject().apply {
                if (callId != null) put("call_id", callId)
                if (deviceId != null) put("device_id", deviceId)
                if (success != null) put("success", success)
                put("log_text", trimmed)
            }

            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    val url = URL(URL_STR)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; utf-8")
                    conn.setRequestProperty("X-Bridge-Secret", BRIDGE_SECRET)
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    conn.outputStream.use { os ->
                        os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }

                    val responseCode = conn.responseCode
                    conn.disconnect()
                    Log.d(TAG, "계측 로그 전송(call_id=$callId, 시도=$attempt): $responseCode")
                    if (responseCode in 200..299) return@withContext true
                } catch (e: Exception) {
                    Log.e(TAG, "계측 로그 전송 실패(call_id=$callId, 시도=$attempt/$MAX_ATTEMPTS)", e)
                }
                if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
            Log.e(TAG, "계측 로그 전송 최종 실패(call_id=$callId)")
            false
        }
}
