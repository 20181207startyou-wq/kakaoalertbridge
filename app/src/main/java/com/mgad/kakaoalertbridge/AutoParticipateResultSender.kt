package com.mgad.kakaoalertbridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// PartnersAutoParticipateService가 상담참여 시도 결과를 백엔드에 보고할 때 사용.
// HeartbeatSender와 동일한 HttpURLConnection + X-Bridge-Secret 패턴.
//
// 2026-09-15: 기존엔 send()가 자체 스코프(scope.launch)로 fire-and-forget 전송하고 즉시
// 리턴해서, 호출부(runParticipateFlow)의 finally가 실제 전송 완료를 기다리지 않고 먼저
// 끝나버릴 수 있었다(홈 화면 복귀 직후 프로세스가 정리되는 타이밍과 겹치면 전송이 끝나기
// 전에 끊길 위험). 서버 쪽 조사로 이번 "결과 미보고" 사고 자체는 실제로는 보고가 다 왔던
// 것으로 확인됐지만(서버의 완료판정 로직 버그), fire-and-forget 자체는 별개로 존재하던
// 취약점이라 suspend 함수로 바꿔 호출부가 전송 완료(또는 재시도 소진)까지 반드시 기다리게
// 하고, 일시적 네트워크 실패에 대비해 짧은 간격으로 최대 2회 재시도한다.
object AutoParticipateResultSender {
    private const val TAG = "AutoParticipateResult"
    private const val SERVER_URL_BASE = "https://app.mgad.kr/api/calls"
    // backend/.env의 BRIDGE_SECRET_KEY와 동일해야 함.
    private const val BRIDGE_SECRET = "31b0cce65959eafe3af0fe13de8ea986e2a6bbdc12e66f71"
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 2000L

    suspend fun send(callId: Int, success: Boolean, errorMessage: String?, dryRun: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("success", success)
                put("dry_run", dryRun)
                if (errorMessage != null) put("error_message", errorMessage)
            }

            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    val url = URL("$SERVER_URL_BASE/$callId/auto-participate-result")
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
                    Log.d(TAG, "자동참여 결과 전송(call_id=$callId, success=$success, dryRun=$dryRun, 시도=$attempt): $responseCode")
                    if (responseCode in 200..299) return@withContext true
                } catch (e: Exception) {
                    Log.e(TAG, "자동참여 결과 전송 실패(call_id=$callId, 시도=$attempt/$MAX_ATTEMPTS)", e)
                }
                if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
            Log.e(TAG, "자동참여 결과 전송 최종 실패(call_id=$callId) - ${MAX_ATTEMPTS}회 모두 실패")
            false
        }
}
