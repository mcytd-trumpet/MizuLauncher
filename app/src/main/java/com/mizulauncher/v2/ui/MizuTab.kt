package com.mizulauncher.v2.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 2.0 的页面拓扑。
 *
 * ## 为什么只有五个一级页面
 * 旧版侧边栏塞了 11 项，用户每次都要在列表里找。人的工作记忆大约是 5±2 项，
 * 超过这个数就要"读"而不是"认"。所以 2.0 把「依赖、日志、关于、控制布局」
 * 这些低频页收进「设置」，一级只留五个真正每天要用的。
 *
 * ## 底部导航而不是抽屉
 * 抽屉需要两次操作（打开、选择），底部导航一次。手机是单手操作，
 * 底部正是拇指最容易够到的区域（拇指热区理论）。
 *
 * ## 图标是有含义的，不是随便挑的
 * - 首页：房子（位置感）
 * - 下载：向下的箭头（"取"的动作）
 * - 账号：人形轮廓（身份）
 * - 运行时：芯片（Java 运行时确实是"跑在芯片上的东西"）
 * - 设置：齿轮（行业惯例，不用再解释）
 */
enum class MizuTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "首页", Icons.Rounded.Home),
    Download("download", "下载", Icons.Rounded.Download),
    Account("account", "账号", Icons.Rounded.AccountCircle),
    Runtime("runtime", "运行时", Icons.Rounded.Memory),
    Settings("settings", "设置", Icons.Rounded.Settings);

    companion object {
        fun ofRoute(route: String?): MizuTab = entries.firstOrNull { it.route == route } ?: Home
    }
}

/**
 * 二级页面（从一级页面推入）。
 *
 * 这些页面盖住底部导航——因为它们属于"深入"而不是"并列"。
 * 用户按返回能明确回到来的地方，层级关系不会乱。
 */
object MizuRoutes {
    const val LOG = "log"
    const val ABOUT = "about"
    const val DEPENDENCIES = "dependencies"
    const val INSTANCE_DETAIL = "instance/{id}"

    fun instanceDetail(id: String) = "instance/$id"
}
