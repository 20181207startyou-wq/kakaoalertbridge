package com.mgad.kakaoalertbridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    val context = androidx.compose.ui.platform.LocalContext.current

    fun checkStatus() {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        enabled = enabledListeners.contains(context.packageName)
    }

    checkStatus()

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
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) {
            Text("알림 접근 권한 설정 열기")
        }
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
