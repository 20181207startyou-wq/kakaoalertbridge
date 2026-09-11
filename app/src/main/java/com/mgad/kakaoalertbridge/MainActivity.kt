package com.mgad.kakaoalertbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mgad.kakaoalertbridge.ui.theme.KakaoAlertBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 포그라운드 서비스 알림(및 카카오톡 알림톡 감지 시 생기는 일반 알림은 없지만,
        // Android 13+에서는 알림을 하나라도 띄우려면 런타임 권한이 별도로 필요함.
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            KakaoAlertBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StatusScreen()
                }
            }
        }
    }
}

@Composable
fun StatusScreen() {
    var enabled by remember { mutableStateOf(false) }
    var batteryOptimizationIgnored by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    var autoParticipateEnabled by remember { mutableStateOf(AutoParticipateSettings.isEnabled(context)) }
    var businessHoursAutoParticipateEnabled by remember { mutableStateOf(AutoParticipateSettings.isBusinessHoursEnabled(context)) }
    var dryRunMode by remember { mutableStateOf(AutoParticipateSettings.isDryRun(context)) }
    var partnersPackageName by remember { mutableStateOf(AutoParticipateSettings.getPartnersPackageName(context)) }
    var tabButtonText by remember { mutableStateOf(AutoParticipateSettings.getTabButtonText(context)) }
    var participateButtonText by remember { mutableStateOf(AutoParticipateSettings.getParticipateButtonText(context)) }

    fun checkStatus() {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        enabled = enabledListeners.contains(context.packageName)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        val enabledAccessibilityServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        accessibilityEnabled = enabledAccessibilityServices.contains(
            "${context.packageName}/${context.packageName}.PartnersAutoParticipateService"
        )
    }

    checkStatus()

    // 설정 화면(알림 접근 권한 / 배터리 최적화 예외)에 다녀온 뒤 화면으로 돌아오면
    // 바로 상태가 갱신되도록, 두 화면 모두 이 런처로 실행해서 결과 콜백에서 재확인한다.
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { checkStatus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (enabled) {
                "✅ 알림 접근 권한 켜짐 - 정상 작동 중"
            } else {
                "❌ 알림 접근 권한이 꺼져있음\n아래 버튼을 눌러서\n'MG애드 콜 알림 감지'를 켜주세요"
            }
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        Button(onClick = {
            settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) {
            Text("알림 접근 권한 설정 열기")
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(20.dp))
        Text(
            text = if (batteryOptimizationIgnored) {
                "✅ 배터리 최적화 예외 적용됨"
            } else {
                "❌ 배터리 최적화 예외가 꺼져있음\n버튼을 눌러 예외를 적용해주세요\n(제조사 자체 절전기능은 폰 설정에서\n별도로 확인이 필요할 수 있습니다)"
            }
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                settingsLauncher.launch(intent)
            },
            enabled = !batteryOptimizationIgnored
        ) {
            Text(if (batteryOptimizationIgnored) "배터리 최적화 예외 적용됨" else "배터리 최적화 예외 요청")
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(20.dp))
        Text(
            text = if (accessibilityEnabled) {
                "✅ 야간 자동참여 접근성 서비스 켜짐"
            } else {
                "❌ 야간 자동참여 접근성 서비스 꺼짐\n아래 버튼을 눌러서\n'MG애드 야간 상담참여 자동확보'를 켜주세요"
            }
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        Button(onClick = {
            settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("접근성 서비스 설정 열기")
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("업무시간 외 자동참여 활성화")
            Switch(
                checked = autoParticipateEnabled,
                onCheckedChange = {
                    autoParticipateEnabled = it
                    AutoParticipateSettings.setEnabled(context, it)
                }
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("업무시간 중 자동참여 활성화")
            Switch(
                checked = businessHoursAutoParticipateEnabled,
                onCheckedChange = {
                    businessHoursAutoParticipateEnabled = it
                    AutoParticipateSettings.setBusinessHoursEnabled(context, it)
                }
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("드라이런 모드(로그만, 실제 클릭 안 함)")
            Switch(
                checked = dryRunMode,
                onCheckedChange = {
                    dryRunMode = it
                    AutoParticipateSettings.setDryRun(context, it)
                }
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        OutlinedTextField(
            value = partnersPackageName,
            onValueChange = {
                partnersPackageName = it
                AutoParticipateSettings.setPartnersPackageName(context, it)
            },
            label = { Text("파트너스 앱 패키지명") },
            singleLine = true,
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(6.dp))
        OutlinedTextField(
            value = tabButtonText,
            onValueChange = {
                tabButtonText = it
                AutoParticipateSettings.setTabButtonText(context, it)
            },
            label = { Text("견적입찰 탭 텍스트") },
            singleLine = true,
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(6.dp))
        OutlinedTextField(
            value = participateButtonText,
            onValueChange = {
                participateButtonText = it
                AutoParticipateSettings.setParticipateButtonText(context, it)
            },
            label = { Text("상담참여 버튼 텍스트") },
            singleLine = true,
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(20.dp))
        Text(
            text = "v${BuildConfig.GIT_COMMIT_HASH} · ${BuildConfig.BUILD_TIMESTAMP}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
fun StatusScreenPreview() {
    KakaoAlertBridgeTheme {
        StatusScreen()
    }
}
