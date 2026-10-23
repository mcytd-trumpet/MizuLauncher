package com.mizulauncher.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mizulauncher.auth.DeviceCode
import com.mizulauncher.auth.MinecraftProfile
import androidx.compose.material3.Button
import com.mizulauncher.ui.components.GlassCard

/**
 * 账户页：微软正版登录 / 离线模式切换。
 *
 * 登录用设备码流程 —— 不弹 WebView，用户拿手机浏览器打开链接输入验证码即可。
 */
@Composable
fun AccountScreen(
    profile: MinecraftProfile?,
    deviceCode: DeviceCode?,
    busy: Boolean,
    status: String?,
    error: String?,
    offlineName: String,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    onOfflineNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            profile != null -> LoggedInCard(profile, onLogout)
            deviceCode != null -> DeviceCodeCard(deviceCode, busy, status, onCancelLogin)
            else -> LoginEntryCard(busy, error, onStartLogin)
        }

        // 离线模式始终可用，但正版登录时不以它为主
        GlassCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("离线模式", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "不登录也能玩，仅限单机。进不了正版服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = offlineName,
                    onValueChange = onOfflineNameChange,
                    label = { Text("玩家名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    }
}

@Composable
private fun LoggedInCard(profile: MinecraftProfile, onLogout: () -> Unit) {
    GlassCard {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(profile.name, size = 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                profile.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                profile.uuid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "  微软正版  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
        }
    }
}

@Composable
private fun LoginEntryCard(busy: Boolean, error: String?, onStartLogin: () -> Unit) {
    GlassCard {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar("?", size = 72.dp)
            Spacer(Modifier.height(12.dp))
            Text("未登录", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "登录微软账号即可进正版服务器，皮肤也能正常显示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onStartLogin,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "正在获取验证码…" else "登录微软账号")
            }
        }
    }
}

@Composable
private fun DeviceCodeCard(
    code: DeviceCode,
    busy: Boolean,
    status: String?,
    onCancel: () -> Unit
) {
    GlassCard {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("在浏览器中完成登录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "打开下面的地址，输入验证码并登录微软账号",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))
            // 验证码放大展示，方便照着念
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    code.userCode,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                code.verificationUri,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    status ?: "等待你完成登录…",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onCancel, enabled = !busy) { Text("取消") }
        }
    }
}

/** 首字母头像。有皮肤时可以换成真正的 Skin 渲染，这里不引入网络依赖。 */
@Composable
fun Avatar(name: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary
    )
    val tint = colors[(name.hashCode().let { if (it < 0) -it else it }) % colors.size]
    Box(
        modifier = Modifier
            .size(size)
            .background(
                Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.6f))),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1).uppercase().ifBlank { "?" },
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value / 2.4f).sp
        )
    }
}
