package com.mgad.kakaoalertbridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// PartnersAutoParticipateService가 상담참여 시도 결과를 백엔드에 보고할 때 사용.
// HeartbeatSender와 동일한 HttpURLConnection + X-Bridge-Secret 패턴.
object AutoParticipateResultSender {
    private const val TAG = "AutoParticipateResult"
    private const val SERVER_URL_BASE = "https://app.mgad.kr/api/calls"
    // backend/.env의 BRIDGE_SECRET_KEY와 동일해야 함.
    private const val BRIDGE_SECRET = "31b0cce65959eafe3af0fe13de8ea986e2a6bbdc12e66f71"

    private val scope = CoroutineScope(Dispatchers.IO)

    fun send(callId: Int, success: Boolean, errorMessage: String?, dryRun: Boolean) {
        scope.launch {
            try {
                val url = URL("$SERVER_URL_BASE/$callId/auto-participate-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("X-Bridge-Secret", BRIDGE_SECRET)
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val payload = JSONObject().apply {
                    put("success", success)
                    put("dry_run", dryRun)
                    if (errorMessage != null) put("error_message", errorMessage)
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                Log.d(TAG, "자동참여 결과 전송(call_id=$callId, success=$success, dryRun=$dryRun): $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "자동참여 결과 전송 실패(call_id=$callId)", e)
            }
        }
    }
}
