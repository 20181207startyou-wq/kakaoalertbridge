package com.mgad.kakaoalertbridge

import android.content.Context

// 업무시간 외 "상담참여" 자동확보 설정값. 이 환경엔 파트너스 앱이 설치된 실기기가 없어
// 정확한 패키지명/버튼 텍스트를 미리 확인할 수 없었음 - 재배포 없이 현장에서 값을 튜닝할 수
// 있도록 SharedPreferences로 뺐다. dryRunMode=true가 기본값이라, 실제 클릭까지는 항상
// 사용자가 명시적으로 드라이런을 꺼야만 일어난다(안전 기본값).
//
// 2026-09-14: autoParticipateEnabled와 패키지명이 둘 다 빈 기본값(false/"")이었던 탓에,
// 실기기에서 이 설정 화면을 한 번도 직접 열어 채워넣지 않으면 트리거가 서버 호출조차
// 없이 완전히 조용히 종료되는 사고가 있었음(주말 콜 전수조사로 확인 - 배포 이래 실기기에서
// 실제로 성공한 자동참여가 단 한 건도 없었음, DB에 남은 유일한 기록은 개발자가 리포트
// API를 직접 호출한 테스트였음). 패키지명은 이미 다른 파일(KakaoNotificationListenerService.kt의
// 간판의품격 자체 앱 필터링 로직)에서 com.classy.ganpoompartner로 확인된 값이라 기본값으로
// 박아넣고, 마스터 토글도 기본 켜짐으로 바꿔 "설정 화면을 한 번도 안 열어도" 최소한
// 드라이런으로는 동작이 시작되게 한다. 설정 화면에서 값을 바꾸면 당연히 그 값이 우선한다.
object AutoParticipateSettings {
    private const val PREFS_NAME = "auto_participate_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BUSINESS_HOURS_ENABLED = "business_hours_enabled"
    private const val KEY_DRY_RUN = "dry_run"
    private const val KEY_PACKAGE_NAME = "partners_package_name"
    private const val KEY_TAB_TEXT = "tab_button_text"
    private const val KEY_PARTICIPATE_TEXT = "participate_button_text"
    private const val KEY_DISMISS_TEXT = "dismiss_button_text"

    const val DEFAULT_TAB_TEXT = "견적입찰"
    const val DEFAULT_PARTICIPATE_TEXT = "상담참여"
    const val DEFAULT_PACKAGE_NAME = "com.classy.ganpoompartner"
    // 2026-09-16: 콜 709 계측 로그로 확정 - 오래 유휴 상태였다가 파트너스 앱 프로세스가
    // 재생성될 때, 이전 실행에서 닫지 않고 남겨둔 "상담참여가 완료되었습니다" 확인
    // 다이얼로그가 그대로 복원되어 3단계(견적입찰 탭 탐색)를 막았다. 이 버튼을 눌러 닫고
    // 탐색을 재시도한다.
    const val DEFAULT_DISMISS_TEXT = "확인"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

    // 업무시간 중 자동참여 - 직원들이 바빠서 앱을 못 볼 때 마감(슬롯)을 놓치지 않기 위한
    // 별도 스위치. 이게 켜져 있으면 업무시간에도 자동참여를 시도하지만, 그 이후 처리는
    // 기존 콜 생성 로직(hold 없음, 평소와 동일한 즉시 알림)을 그대로 탄다 - 서버의
    // is_business_hours() 조기 리턴이 hold 로직 자체를 업무시간엔 거치지 않게 해주므로
    // 앱 쪽에서 추가로 분기할 필요가 없다. 기본값은 꺼짐(필요할 때만 켜서 사용).
    fun isBusinessHoursEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUSINESS_HOURS_ENABLED, false)
    fun setBusinessHoursEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BUSINESS_HOURS_ENABLED, value).apply()

    // 드라이런 기본 ON - 실제 클릭을 켜려면 사용자가 명시적으로 꺼야 함(안전 기본값).
    fun isDryRun(context: Context): Boolean = prefs(context).getBoolean(KEY_DRY_RUN, true)
    fun setDryRun(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_DRY_RUN, value).apply()

    fun getPartnersPackageName(context: Context): String =
        prefs(context).getString(KEY_PACKAGE_NAME, DEFAULT_PACKAGE_NAME) ?: DEFAULT_PACKAGE_NAME
    fun setPartnersPackageName(context: Context, value: String) =
        prefs(context).edit().putString(KEY_PACKAGE_NAME, value).apply()

    fun getTabButtonText(context: Context): String =
        prefs(context).getString(KEY_TAB_TEXT, DEFAULT_TAB_TEXT) ?: DEFAULT_TAB_TEXT
    fun setTabButtonText(context: Context, value: String) =
        prefs(context).edit().putString(KEY_TAB_TEXT, value).apply()

    fun getParticipateButtonText(context: Context): String =
        prefs(context).getString(KEY_PARTICIPATE_TEXT, DEFAULT_PARTICIPATE_TEXT) ?: DEFAULT_PARTICIPATE_TEXT
    fun setParticipateButtonText(context: Context, value: String) =
        prefs(context).edit().putString(KEY_PARTICIPATE_TEXT, value).apply()

    fun getDismissButtonText(context: Context): String =
        prefs(context).getString(KEY_DISMISS_TEXT, DEFAULT_DISMISS_TEXT) ?: DEFAULT_DISMISS_TEXT
    fun setDismissButtonText(context: Context, value: String) =
        prefs(context).edit().putString(KEY_DISMISS_TEXT, value).apply()

    // 패키지명이 비어있으면 어느 앱을 열어야 할지 알 수 없으므로 설정이 안 된 것으로 간주.
    fun isConfigured(context: Context): Boolean = getPartnersPackageName(context).isNotBlank()
}
