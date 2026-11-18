# 2026-11-18(item 6): R8/난독화 keep 규칙.
# 매니페스트에 선언된 컴포넌트(서비스/리시버/액티비티)는 R8이 진입점으로 자동 유지하지만,
# 접근성/알림리스너처럼 시스템이 리플렉션/바인딩으로 다루는 클래스는 안전하게 명시적으로도 keep.

-keep class com.mgad.kakaoalertbridge.PartnersAutoParticipateService { *; }
-keep class com.mgad.kakaoalertbridge.KakaoNotificationListenerService { *; }
-keep class com.mgad.kakaoalertbridge.FcmMessagingService { *; }
-keep class com.mgad.kakaoalertbridge.WakeAndLaunchActivity { *; }
-keep class com.mgad.kakaoalertbridge.BootReceiver { *; }

# Firebase Cloud Messaging(리플렉션 기반 초기화) 보존.
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# BuildConfig(주입된 BRIDGE_SECRET 등) 보존.
-keep class com.mgad.kakaoalertbridge.BuildConfig { *; }
