package com.mizulauncher.v2.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mizulauncher.compat.MotionLevel
import com.mizulauncher.compat.MotionSpec
import com.mizulauncher.skin.SkinPreview3D
import com.mizulauncher.skin.TextureState
import com.mizulauncher.ui.design.MizuBadge
import com.mizulauncher.ui.design.MizuCard
import com.mizulauncher.ui.design.currentMotionLevel
import com.mizulauncher.ui.design.mizuColors
import com.mizulauncher.v2.state.AccountState
import com.mizulauncher.v2.state.LoginKind
import com.mizulauncher.v2.state.YggdrasilAccount
import com.mizulauncher.v2.ui.SectionHeader

/**
 * 账号页。
 *
 * ## 三种登录方式为什么排成这样
 *
 * 1. **微软正版**放最上面。它是唯一能进正版服务器的路径，优先级最高；
 * 2. **外置登录（LittleSkin / 各类皮肤站）**紧随其后。国内玩家大量使用这个，
 *    而且它带皮肤——很多人装外置登录的目的就是"用第三方皮肤"；
 * 3. **离线**不单独占一块，而是作为"没有账号时也能玩"的兜底说明写在底部。
 *    单独做一个离线登录卡片会让新手以为必须先选一个。
 *
 * ## 3D 皮肤预览放在页面顶部
 * 它承担"确认身份"的职责。登录成功后立刻在这里看到自己的角色，
 * 是最直接的反馈——比弹一个"登录成功"的 toast 强得多。
 */
@Composable
fun AccountScreen(
    state: AccountState,
    onMicrosoftLogin: () -> Unit,
    onCancelMicrosoftLogin: () -> Unit,
    onServerChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDiscover: () -> Unit,
    onYggLogin: () -> Unit,
    onYggLogout: () -> Unit,
    onLogoutAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("preview") {
            SkinPreviewCard(state)
        }

        // ---------------- 微软 ----------------
        item("ms_header") { SectionHeader(title = "微软账户", subtitle = "可进入正版验证服务器") }
        item("ms_card") {
            MicrosoftCard(
                state = state,
                onLogin = onMicrosoftLogin,
                onCancel = onCancelMicrosoftLogin,
                onLogout = onLogoutAll
            )
        }

        // ---------------- 外置登录 ----------------
        item("ygg_header") {
            SectionHeader(
                title = "外置登录",
                subtitle = "LittleSkin 等皮肤站，支持自定义皮肤"
            )
        }
        item("ygg_card") {
            YggdrasilCard(
                state = state,
                onServerChange = onServerChange,
                onEmailChange = onEmailChange,
                onPasswordChange = onPasswordChange,
                onDiscover = onDiscover,
                onLogin = onYggLogin,
                onLogout = onYggLogout
            )
        }

        // ---------------- 已登录的外置账号 ----------------
        if (state.yggdrasilAccounts.isNotEmpty()) {
            item("ygg_list_header") {
                SectionHeader(title = "已保存的外置账号", subtitle = "${state.yggdrasilAccounts.size} 个")
            }
            items(state.yggdrasilAccounts.size) { index ->
                YggAccountRow(state.yggdrasilAccounts[index])
            }
        }

        // ---------------- 离线说明 ----------------
        item("offline") {
            MizuCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("离线模式", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "不登录也能启动游戏，角色名会显示为「Player」。可以进入离线服务器，" +
                                "但无法进入需要正版验证的服务器，也不会显示自定义皮肤。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 皮肤预览卡片。
 *
 * 视觉上刻意做成"人物展示台"：渐变背景 + 居中 3D 模型 + 下方名字。
 * 自转动画让静态图有了生命感——这是唯一一处允许"为了好看而动画"的地方，
 * 因为它是这个页面的情感焦点，而不是功能区。
 */
@Composable
private fun SkinPreviewCard(state: AccountState) {
    val name = when (state.kind) {
        LoginKind.Microsoft -> state.microsoft?.name
        LoginKind.Yggdrasil -> state.yggdrasilAccounts.firstOrNull()?.displayName
        else -> null
    }
    val skin = state.yggdrasilAccounts.firstOrNull()?.skin

    val primary = MaterialTheme.colorScheme.primary

    MizuCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            primary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    if (skin is TextureState.Ready) {
                        SkinPreview3D(
                            skin = skin.image,
                            slim = skin.slim,
                            modifier = Modifier.size(150.dp),
                            motionLevel = currentMotionLevel
                        )
                    } else {
                        WaitingSilhouette()
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = name ?: "未登录",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (name != null) {
                    Spacer(Modifier.height(4.dp))
                    MizuBadge(
                        text = when (state.kind) {
                            LoginKind.Microsoft -> "微软正版"
                            LoginKind.Yggdrasil -> "外置登录"
                            else -> "离线"
                        },
                        container = primary.copy(alpha = 0.18f),
                        content = primary
                    )
                }
            }
        }
    }
}

/** 未登录时的人形轮廓：带一点呼吸感，暗示"这里将来会站一个人" */
@Composable
private fun WaitingSilhouette() {
    val level = currentMotionLevel
    val alpha = if (level == MotionLevel.None) {
        0.35f
    } else {
        val t = rememberInfiniteTransition(label = "silhouette")
        val a by t.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.42f,
            animationSpec = infiniteRepeatable(
                tween(if (level == MotionLevel.Reduced) 2400 else 1600),
                RepeatMode.Reverse
            ),
            label = "silhouetteAlpha"
        )
        a
    }

    val c = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(c))
        Spacer(Modifier.height(5.dp))
        Box(Modifier.width(64.dp).height(56.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = alpha * 0.8f)))
    }
}

/**
 * 微软登录卡。
 *
 * 设备码流程的 UI 要点：**验证码必须又大又能复制**。
 * 用户要去浏览器里输入 8 位码，用小字显示等于让所有人都输错一次。
 */
@Composable
private fun MicrosoftCard(
    state: AccountState,
    onLogin: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit
) {
    MizuCard(modifier = Modifier.fillMaxWidth()) {
        if (state.microsoft != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.mizuColors.success,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.microsoft.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "UUID ${state.microsoft.uuid.take(8)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onLogout) {
                    Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("退出")
                }
            }
        } else {
            Text(
                text = "用微软账户登录，可以进入 Hypixel 等需要正版验证的服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // 设备码展示区：高度会变化，用 AnimatedContent 平滑过渡
            AnimatedContent(
                targetState = state.deviceCode,
                transitionSpec = {
                    (fadeIn(tween(MotionSpec.Short)) + expandVertically()) togetherWith
                        (fadeOut(tween(MotionSpec.Short)) + shrinkVertically())
                },
                label = "deviceCode"
            ) { code ->
                if (code != null) {
                    Column {
                        androidx.compose.material3.Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "在浏览器中输入这个验证码",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = code.userCode,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        letterSpacing = androidx.compose.ui.unit.TextUnit(
                                            4f,
                                            androidx.compose.ui.unit.TextUnitType.Sp
                                        )
                                    )
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = code.verificationUri,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                                Text("取消")
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !state.busy,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("使用微软账户登录")
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 外置登录表单。
 *
 * ## 服务器地址这一栏的坑
 * LittleSkin 的首页是 `https://littleskin.cn`，但 API 是 `https://littleskin.cn/api/yggdrasil`。
 * 用户填哪个都可能。这里不要求他懂——填完地址自动探测（读 `X-Authlib-Injector-API-Location`
 * 响应头），探测到了就把结果告诉他；探测不到再给具体建议，而不是让他在两个地址之间瞎猜。
 */
@Composable
private fun YggdrasilCard(
    state: AccountState,
    onServerChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDiscover: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val form = state.form
    var showPassword by remember { mutableStateOf(false) }

    MizuCard(modifier = Modifier.fillMaxWidth()) {
        if (state.kind == LoginKind.Yggdrasil && state.yggdrasilAccounts.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Public,
                    contentDescription = null,
                    tint = MaterialTheme.mizuColors.success,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.yggdrasilAccounts.first().displayName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = state.yggdrasilAccounts.first().apiRoot.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onLogout) {
                    Text("退出")
                }
            }
            return@MizuCard
        }

        OutlinedTextField(
            value = form.server,
            onValueChange = onServerChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            placeholder = { Text("https://littleskin.cn") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            trailingIcon = {
                IconButton(onClick = onDiscover, enabled = form.server.isNotBlank() && !form.discovering) {
                    if (form.discovering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "探测 API")
                    }
                }
            }
        )

        // 探测结果：成功显示真实 API 地址，失败显示原因。这一条能省掉大量"我填对了为什么不行"
        AnimatedVisibility(visible = form.hint != null || form.error != null) {
            Text(
                text = form.hint ?: form.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (form.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.mizuColors.success,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = form.email,
            onValueChange = onEmailChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("邮箱") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (showPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        )

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = form.canSubmit && !state.busy,
            shape = MaterialTheme.shapes.medium
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("登录")
        }

        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "登录后会自动下载 authlib-injector 并注入到游戏 JVM，" +
                "这样游戏内才能显示你的自定义皮肤。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun YggAccountRow(account: YggdrasilAccount) {
    MizuCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                when (val s = account.skin) {
                    is TextureState.Ready -> SkinPreview3D(
                        skin = s.image,
                        slim = s.slim,
                        modifier = Modifier.size(40.dp),
                        autoRotate = false,
                        motionLevel = currentMotionLevel
                    )
                    else -> Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = account.profileName ?: account.apiRoot.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
