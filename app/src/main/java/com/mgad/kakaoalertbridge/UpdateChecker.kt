package com.mgad.kakaoalertbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 인앱 자동 업데이트 - 새 버전이 나올 때마다 사용자가 직접 링크 요청/다운로드/설치를 반복하는
// 번거로움을 없애기 위함. 하트비트 루프(KakaoNotificationListenerService, 5분 주기)를 타고
// PERIODIC_CHECK_INTERVAL_MS(6시간)마다 한 번만 실제로 서버를 조회하고, 새 버전이 있으면
// 백그라운드로 미리 다운로드해둔 뒤 알림을 띄운다 - 탭하면 바로 설치 확인창으로 이어진다.
// 설정화면의 "지금 업데이트 확인" 버튼은 이 게이트를 무시하고 즉시 checkNow()를 호출한다.
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val VERSION_URL = "https://app.mgad.kr/api/calls/app-latest-version"
    // backend/.env의 BRIDGE_SECRET_KEY와 동일해야 함(HeartbeatSender/KakaoNotificationListenerService와 동일 값).
    private const val BRIDGE_SECRET = "31b0cce65959eafe3af0fe13de8ea986e2a6bbdc12e66f71"

    private const val PREFS_NAME = "update_checker"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val PERIODIC_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val UPDATE_CHANNEL_ID = "app_update"
    private const val UPDATE_NOTIFICATION_ID = 2001
    private const val APK_FILE_NAME = "kakaoalertbridge-update.apk"

    private val scope = CoroutineScope(Dispatchers.IO)

    data class LatestVersion(val version: String, val downloadUrl: String, val releaseNotes: String?)

    sealed class CheckResult {
        data class UpdateAvailable(val info: LatestVersion) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    // 하트비트 루프 tick마다 호출되지만, 마지막 체크 이후 PERIODIC_CHECK_INTERVAL_MS가
    // 지났을 때만 실제로 서버를 조회한다 - 5분마다 매번 조회하면 불필요한 트래픽이 생긴다.
    fun maybeCheckPeriodic(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - last < PERIODIC_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()

        checkNow(appContext) { result ->
            if (result is CheckResult.UpdateAvailable) {
                Log.d(TAG, "주기 체크: 새 버전 발견(${result.info.version}) - 백그라운드 다운로드 시작")
                downloadAndNotify(appContext, result.info)
            }
        }
    }

    // 설정화면의 "지금 업데이트 확인" 버튼 및 maybeCheckPeriodic 양쪽이 공유하는 실제 체크 로직.
    // onResult는 항상 메인 스레드에서 호출된다.
    fun checkNow(context: Context, onResult: (CheckResult) -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val result = try {
                val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("X-Bridge-Secret", BRIDGE_SECRET)
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    conn.disconnect()
                    Log.w(TAG, "버전 조회 실패: HTTP $code $errorBody")
                    CheckResult.Error("서버 응답 오류($code)")
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = JSONObject(body)
                    val latestVersion = json.optString("version", "")
                    val downloadUrl = json.optString("download_url", "")
                    val releaseNotes = if (json.isNull("release_notes")) null else json.optString("release_notes", null)
                    when {
                        latestVersion.isBlank() || downloadUrl.isBlank() ->
                            CheckResult.Error("서버 응답에 버전 정보 없음")
                        latestVersion == BuildConfig.GIT_COMMIT_HASH ->
                            CheckResult.UpToDate
                        else ->
                            CheckResult.UpdateAvailable(LatestVersion(latestVersion, downloadUrl, releaseNotes))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "업데이트 확인 중 예외", e)
                CheckResult.Error("네트워크 오류: ${e.message}")
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    // 설치 권한(REQUEST_INSTALL_PACKAGES)이 이미 허용돼 있는지 - Android 8(API 26) 미만은
    // 전역 "출처를 알 수 없는 앱" 설정이라 매니페스트 권한만으로 충분해 항상 true.
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    // 설정화면 버튼(포그라운드) 전용 - 다운로드가 끝나면 바로 시스템 설치 확인창을 띄운다.
    fun downloadAndInstallNow(context: Context, info: LatestVersion, onResult: (Boolean, String?) -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val file = downloadApkBlocking(appContext, info.downloadUrl)
            withContext(Dispatchers.Main) {
                if (file == null) {
                    onResult(false, "APK 다운로드 실패")
                    return@withContext
                }
                try {
                    context.startActivity(buildInstallIntent(appContext, file))
                    onResult(true, null)
                } catch (e: Exception) {
                    Log.e(TAG, "설치 인텐트 실행 실패", e)
                    onResult(false, "설치 화면을 여는 중 오류: ${e.message}")
                }
            }
        }
    }

    // 주기 체크(백그라운드) 전용 - 미리 다운로드해두고, 탭하면 설치로 이어지는 알림만 띄운다.
    private fun downloadAndNotify(context: Context, info: LatestVersion) {
        scope.launch {
            val file = downloadApkBlocking(context, info.downloadUrl) ?: run {
                Log.w(TAG, "주기 체크: 백그라운드 다운로드 실패(${info.version})")
                return@launch
            }
            withContext(Dispatchers.Main) { showUpdateNotification(context, file, info) }
        }
    }

    private fun apkFile(context: Context): File = File(context.getExternalFilesDir(null), APK_FILE_NAME)

    private fun downloadApkBlocking(context: Context, downloadUrl: String): File? {
        return try {
            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "APK 다운로드 실패: HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val file = apkFile(context)
            conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            conn.disconnect()
            file
        } catch (e: Exception) {
            Log.e(TAG, "APK 다운로드 중 예외", e)
            null
        }
    }

    private fun buildInstallIntent(context: Context, file: File): Intent {
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun showUpdateNotification(context: Context, file: File, info: LatestVersion) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "앱 업데이트 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "MG애드 콜 알림 브릿지의 새 버전이 있을 때 알려줍니다." }
            nm.createNotificationChannel(channel)
        }

        // 설치 권한이 없으면 탭했을 때 설치창 대신 권한 설정 화면으로 보낸다 - 사용자가 권한을
        // 허용한 뒤 알림을 다시 탭하면(또는 설정화면의 "지금 업데이트 확인"으로) 이어서 설치할 수 있음.
        val (targetIntent, contentText) = if (canInstallPackages(context)) {
            buildInstallIntent(context, file) to "탭하여 설치하세요 (v${info.version})"
        } else {
            requestInstallPermissionIntent(context) to "설치하려면 먼저 \"출처를 알 수 없는 앱\" 권한을 허용해주세요"
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, targetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setContentTitle("새 업데이트가 있습니다 (v${info.version})")
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(info.releaseNotes?.takeIf { it.isNotBlank() } ?: contentText)
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            nm.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 권한이 거부된 경우 - 알림만 못 띄울 뿐 다음 수동 체크로 설치 가능.
            Log.e(TAG, "업데이트 알림 표시 실패(알림 권한 없음)", e)
        }
    }
}
