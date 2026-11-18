import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

fun gitCommitHash(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.ifBlank { "unknown" }
} catch (e: Exception) {
    "unknown"
}

fun buildTimestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
    sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
    return sdf.format(Date())
}

// 2026-11-18(item 6): BRIDGE_SECRET를 소스 평문에서 제거하고 빌드 시 주입한다. 레포 루트의
// secrets.properties(gitignore됨)에서 읽고, 없으면 빈 값으로 두되 빌드 로그에 경고한다(값 교체 아님).
fun bridgeSecret(): String {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) {
        val p = Properties()
        f.inputStream().use { p.load(it) }
        val v = p.getProperty("BRIDGE_SECRET")
        if (!v.isNullOrBlank()) return v
    }
    println("WARNING: secrets.properties의 BRIDGE_SECRET가 없습니다 - BRIDGE_SECRET가 빈 값으로 빌드됩니다(서버 인증 실패).")
    return ""
}

android {
    namespace = "com.mgad.kakaoalertbridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mgad.kakaoalertbridge"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GIT_COMMIT_HASH", "\"${gitCommitHash()}\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"${buildTimestamp()}\"")
        buildConfigField("String", "BRIDGE_SECRET", "\"${bridgeSecret()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 2026-11-18(item 6): R8/난독화 활성화. 접근성/알림리스너/FCM/부팅 리시버 등 매니페스트
            // 선언 컴포넌트는 R8이 자동 유지하지만 proguard-rules.pro에 명시 keep도 둔다.
            // 주의: 현재 배포(build.sh)는 debug APK라 이 설정은 release 빌드에서만 적용된다 -
            // 실기기 검증 전까지 debug 배포를 유지한다(보고 참조).
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}