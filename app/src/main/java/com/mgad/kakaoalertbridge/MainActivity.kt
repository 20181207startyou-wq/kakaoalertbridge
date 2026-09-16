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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// 항목이 계속 늘면서(권한 3종 + 토글 3종 + 텍스트필드 3종) 세로로 끝없이 길어져 화면이
// 잘리던 문제(2026-09-15) - 섹션 카드로 묶고 스크롤을 명시적으로 허용해 해결. 예전엔
// Column에 verticalScroll이 아예 없어서 화면보다 콘텐츠가 길어지면 아래쪽이 그냥 잘렸었음.
@Composable
fun StatusScreen() {
    var enabled by remember { mutableStateOf(false) }
    var batteryOptimizationIgnored by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current

    var autoParticipateEnabled by remember { mutableStateOf(AutoParticipateSettings.isEnabled(context)) }
    var businessHoursAutoParticipateEnabled by remember { mutableStateOf(AutoParticipateSettings.isBusinessHoursEnabled(context)) }
    var dryRunMode by remember { mutableStateOf(AutoParticipateSettings.isDryRun(context)) }
    var partnersPackageName by remember { mutableStateOf(AutoParticipateSettings.getPartnersPackageName(context)) }
    var tabButtonText by remember { mutableStateOf(AutoParticipateSettings.getTabButtonText(context)) }
    var participateButtonText by remember { mutableStateOf(AutoParticipateSettings.getParticipateButtonText(context)) }
    var dismissButtonText by remember { mutableStateOf(AutoParticipateSettings.getDismissButtonText(context)) }

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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SectionCard(title = "1. 기본 권한") {
            StatusLine(
                ok = enabled,
                okText = "✅ 알림 접근 권한 켜짐 - 정상 작동 중",
                badText = "❌ 알림 접근 권한이 꺼져있음\n아래 버튼을 눌러서 'MG애드 콜 알림 감지'를 켜주세요"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) {
                Text("알림 접근 권한 설정 열기")
            }

            Spacer(modifier = Modifier.height(14.dp))
            StatusLine(
                ok = batteryOptimizationIgnored,
                okText = "✅ 배터리 최적화 예외 적용됨",
                badText = "❌ 배터리 최적화 예외가 꺼져있음\n버튼을 눌러 예외를 적용해주세요\n(제조사 자체 절전기능은 폰 설정에서 별도 확인이 필요할 수 있습니다)"
            )
            Spacer(modifier = Modifier.height(8.dp))
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
        }

        SectionCard(title = "2. 자동참여 설정") {
            StatusLine(
                ok = accessibilityEnabled,
                okText = "✅ 야간 자동참여 접근성 서비스 켜짐",
                badText = "❌ 야간 자동참여 접근성 서비스 꺼짐\n아래 버튼을 눌러서 'MG애드 야간 상담참여 자동확보'를 켜주세요"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) {
                Text("접근성 서비스 설정 열기")
            }

            Spacer(modifier = Modifier.height(14.dp))
            ToggleRow(
                label = "업무시간 외 자동참여 활성화",
                checked = autoParticipateEnabled,
                onCheckedChange = {
                    autoParticipateEnabled = it
                    AutoParticipateSettings.setEnabled(context, it)
                }
            )
            ToggleRow(
                label = "업무시간 중 자동참여 활성화",
                checked = businessHoursAutoParticipateEnabled,
                onCheckedChange = {
                    businessHoursAutoParticipateEnabled = it
                    AutoParticipateSettings.setBusinessHoursEnabled(context, it)
                }
            )
            ToggleRow(
                label = "드라이런 모드(로그만, 실제 클릭 안 함)",
                checked = dryRunMode,
                onCheckedChange = {
                    dryRunMode = it
                    AutoParticipateSettings.setDryRun(context, it)
                }
            )
        }

        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "3. 고급 설정 (자주 안 건드리는 항목)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (advancedExpanded) "▾ 접기" else "▸ 펼치기",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (advancedExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = partnersPackageName,
                    onValueChange = {
                        partnersPackageName = it
                        AutoParticipateSettings.setPartnersPackageName(context, it)
                    },
                    label = { Text("파트너스 앱 패키지명") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = tabButtonText,
                    onValueChange = {
                        tabButtonText = it
                        AutoParticipateSettings.setTabButtonText(context, it)
                    },
                    label = { Text("견적입찰 탭 텍스트") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = participateButtonText,
                    onValueChange = {
                        participateButtonText = it
                        AutoParticipateSettings.setParticipateButtonText(context, it)
                    },
                    label = { Text("상담참여 버튼 텍스트") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dismissButtonText,
                    onValueChange = {
                        dismissButtonText = it
                        AutoParticipateSettings.setDismissButtonText(context, it)
                    },
                    label = { Text("완료 확인 다이얼로그 닫기 버튼 텍스트") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "v${BuildConfig.GIT_COMMIT_HASH} · ${BuildConfig.BUILD_TIMESTAMP} 빌드",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, badText: String) {
    Text(
        text = if (ok) okText else badText,
        fontSize = 13.sp,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true)
@Composable
fun StatusScreenPreview() {
    KakaoAlertBridgeTheme {
        StatusScreen()
    }
}
