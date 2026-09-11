package com.mgad.kakaoalertbridge

import android.content.Context

// 업무시간 외 "상담참여" 자동확보 설정값. 이 환경엔 파트너스 앱이 설치된 실기기가 없어
// 정확한 패키지명/버튼 텍스트를 미리 확인할 수 없었음 - 재배포 없이 현장에서 값을 튜닝할 수
// 있도록 SharedPreferences로 뺐다. autoParticipateEnabled=false 또는 dryRunMode=true가
// 기본값이라, 설정을 채우기 전까지는 아무 동작도 하지 않거나(비활성) 로그만 남긴다(드라이런).
object AutoParticipateSettings {
    private const val PREFS_NAME = "auto_participate_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BUSINESS_HOURS_ENABLED = "business_hours_enabled"
    private const val KEY_DRY_RUN = "dry_run"
    private const val KEY_PACKAGE_NAME = "partners_package_name"
    private const val KEY_TAB_TEXT = "tab_button_text"
    private const val KEY_PARTICIPATE_TEXT = "participate_button_text"

    const val DEFAULT_TAB_TEXT = "견적입찰"
    const val DEFAULT_PARTICIPATE_TEXT = "상담참여"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
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

    fun getPartnersPackageName(context: Context): String = prefs(context).getString(KEY_PACKAGE_NAME, "") ?: ""
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

    // 패키지명이 비어있으면 어느 앱을 열어야 할지 알 수 없으므로 설정이 안 된 것으로 간주.
    fun isConfigured(context: Context): Boolean = getPartnersPackageName(context).isNotBlank()
}
