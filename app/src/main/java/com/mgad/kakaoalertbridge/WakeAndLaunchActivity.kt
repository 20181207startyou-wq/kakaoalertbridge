package com.mgad.kakaoalertbridge

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
class WakeAndLaunchActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WakeAndLaunch"
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        // 보안 잠금(PIN/패턴/생체인식)이 걸려있으면 이 호출로는 못 넘긴다 - 시스템이 인증
        // 화면을 띄우고 콜백은 취소로 온다. 보안 잠금이 없으면(현재 상태) 즉시 통과.
        keyguardManager?.requestDismissKeyguard(this, null)
    }

    override fun onResume() {
        super.onResume()
        // onCreate 직후 바로 다음 액티비티를 쏘면 우리 윈도우(화면 켜기/잠금해제 플래그)가
        // 실제로 아직 화면에 붙기 전일 수 있어 경합이 생긴다. onResume은 우리 액티비티가
        // 실제로 화면 맨 위로 올라온 뒤에만 호출되므로 여기서 다음 액티비티를 쏜다.
        launchTargetAndFinish()
    }

    private var launched = false

    private fun launchTargetAndFinish() {
        if (launched) return
        val packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (packageName == null) {
            Log.w(TAG, "대상 패키지명 누락 - 종료")
            finish()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.w(TAG, "대상 패키지($packageName) 실행 인텐트를 못 만듦 - 종료")
            finish()
            return
        }
        launched = true
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
        finish()
    }
}
