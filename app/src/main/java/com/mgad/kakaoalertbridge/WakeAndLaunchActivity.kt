package com.mgad.kakaoalertbridge

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity

// 2026-09-15: [2/8단계] 파트너스 앱 전면 전환 대기 시간 초과 재조사 - 8초->15초로 타임아웃을
// 올려도(f92056d) 동일 콜(684)이 다음날 같은 단계에서 다시 실패한 근본 원인. 접근성 서비스는
// 자체 윈도우가 없어 화면을 깨우거나 잠금화면을 넘길 수 없고, PartnersAutoParticipateService가
// 그동안 packageManager.getLaunchIntentForPackage()로 얻은 인텐트를 startActivity()로 바로
// 쏘기만 했는데, 화면이 꺼져있거나 잠금화면(밀어서 잠금해제) 상태면 파트너스 앱 액티비티가
// 태스크 스택에는 올라가도 실제 화면 맨 위(rootInActiveWindow)는 계속 잠금화면(System UI)
// 패키지로 남아있어 대기 로직이 아무리 기다려도 절대 통과할 수 없었다(타임아웃 값과 무관).
//
// 해결: 우리 앱 소유의 이 투명 액티비티를 먼저 띄워 화면을 켜고 잠금화면을 넘긴 뒤(둘 다
// 우리 액티비티의 윈도우 플래그로만 가능 - 파트너스 앱 액티비티에는 이 플래그를 걸 수 없음),
// 그 직후 같은 포그라운드 컨텍스트에서 파트너스 앱을 실행한다. PIN/패턴/생체인식 같은 보안
// 잠금이 걸려있으면(현재 이 기기는 아님 - 보안 잠금 없음 확인됨) keyguard를 넘길 수 없어
// 여전히 실패하는데, 이건 안드로이드 정책상 소프트웨어로 우회 불가능한 영역이라 별도로
// 보안 잠금을 끄는 정책 결정이 필요하다.
//
// 2026-09-16: 위 수정을 설치한 뒤에도 11:33 알림에서 동일 오류가 재발(사용자 설치 확인 후
// 발생) - 이 액티비티가 기대대로 동작하는지 자체가 검증되지 않은 채로 "고쳤다"고 여러 번
// 보고해온 게 문제였음. 그래서 이 클래스의 각 단계(진입/API 호출 성공여부/화면 인터랙티브
// 상태/다음 액티비티 실행까지 걸린 시간)를 전부 로그로 남긴다 - 다음 실패 시 이 로그만
// 보고도 "이 액티비티가 아예 안 불렸는지 / 불렸는데 플래그 적용이 실패했는지 / 플래그는
// 먹었는데 파트너스 앱 실행 자체가 안 됐는지"를 확정할 수 있어야 한다. 원인이 로그로
// 확정되기 전까지는 이 파일에 추측성 수정을 추가하지 않는다.
class WakeAndLaunchActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WakeAndLaunch"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_CALL_ID = "call_id"
        // 서비스가 이 액티비티를 쏘기 직전의 SystemClock.elapsedRealtime() 값을 실어 보낸다 -
        // 두 컴포넌트의 로그를 하나의 기준 시계로 이어 붙여 단계별 소요시간을 계산하기 위함.
        const val EXTRA_TRIGGER_ELAPSED_MS = "trigger_elapsed_ms"
    }

    private var callId: Int = -1
    private var triggerElapsedMs: Long = -1L

    private fun sinceTrigger(): String =
        if (triggerElapsedMs < 0) "?" else "${SystemClock.elapsedRealtime() - triggerElapsedMs}ms"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callId = intent.getIntExtra(EXTRA_CALL_ID, -1)
        triggerElapsedMs = intent.getLongExtra(EXTRA_TRIGGER_ELAPSED_MS, -1L)
        Log.d(TAG, "onCreate 진입(call_id=$callId, 트리거 후 경과=${sinceTrigger()})")

        val powerManager = getSystemService(PowerManager::class.java)
        Log.d(TAG, "onCreate 시점 화면 인터랙티브 상태(isInteractive)=${powerManager?.isInteractive} (call_id=$callId)")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                Log.d(TAG, "setShowWhenLocked(true)/setTurnScreenOn(true) 호출 완료(SDK=${Build.VERSION.SDK_INT}, call_id=$callId)")
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
                Log.d(TAG, "구버전 윈도우 플래그(FLAG_SHOW_WHEN_LOCKED 등) 적용 완료(SDK=${Build.VERSION.SDK_INT}, call_id=$callId)")
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (t: Throwable) {
            // NoSuchMethodError 등 Error 계열도 잡아야 함 - minSdk 24라 API 26 전용 메서드
            // 호출부는 구버전 기기에서 Exception이 아니라 Error로 터질 수 있음.
            Log.e(TAG, "화면 켜기/잠금해제 플래그 설정 중 예외(call_id=$callId)", t)
        }

        try {
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            Log.d(TAG, "KeyguardManager 조회=${keyguardManager != null}, isKeyguardLocked=${keyguardManager?.isKeyguardLocked} (call_id=$callId)")
            if (keyguardManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissError() {
                        Log.w(TAG, "requestDismissKeyguard: onDismissError(call_id=$callId, 트리거 후 경과=${sinceTrigger()})")
                    }
                    override fun onDismissSucceeded() {
                        Log.d(TAG, "requestDismissKeyguard: onDismissSucceeded(call_id=$callId, 트리거 후 경과=${sinceTrigger()})")
                    }
                    override fun onDismissCancelled() {
                        Log.w(TAG, "requestDismissKeyguard: onDismissCancelled(보안 잠금 등으로 취소됐을 가능성, call_id=$callId, 트리거 후 경과=${sinceTrigger()})")
                    }
                })
                Log.d(TAG, "requestDismissKeyguard 호출 완료(콜백 대기, call_id=$callId)")
            } else {
                Log.w(TAG, "requestDismissKeyguard 미호출(keyguardManager=${keyguardManager != null}, SDK=${Build.VERSION.SDK_INT}, call_id=$callId)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "requestDismissKeyguard 호출 중 예외(call_id=$callId)", t)
        }
    }

    override fun onResume() {
        super.onResume()
        val powerManager = getSystemService(PowerManager::class.java)
        Log.d(TAG, "onResume 진입(call_id=$callId, 트리거 후 경과=${sinceTrigger()}, isInteractive=${powerManager?.isInteractive})")
        launchTargetAndFinish()
    }

    private var launched = false

    private fun launchTargetAndFinish() {
        if (launched) return
        val packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        Log.d(TAG, "launchTargetAndFinish 시작(call_id=$callId, target=$packageName, 트리거 후 경과=${sinceTrigger()})")
        if (packageName == null) {
            Log.w(TAG, "대상 패키지명 누락 - 종료(call_id=$callId)")
            finish()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.w(TAG, "대상 패키지($packageName) 실행 인텐트를 못 만듦 - 종료(call_id=$callId)")
            finish()
            return
        }
        launched = true
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(launchIntent)
            Log.d(TAG, "파트너스 앱(${packageName}) startActivity 호출 완료(call_id=$callId, 트리거 후 경과=${sinceTrigger()})")
        } catch (t: Throwable) {
            Log.e(TAG, "파트너스 앱(${packageName}) startActivity 중 예외(call_id=$callId)", t)
        }
        finish()
    }
}
