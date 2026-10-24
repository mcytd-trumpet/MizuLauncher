package com.mizulauncher.skin

/**
 * 皮肤纹理的加载状态。
 *
 * ## 为什么要三态而不是 nullable Bitmap
 * 界面需要区分三件事：还没开始拉、正在拉、拉失败了。
 * 用一个 `ImageBitmap?` 表达，后两者都变成 null，
 * 用户看到的就是"一直空白"，无法判断是网络慢还是角色确实没设皮肤。
 */
sealed interface TextureState {
    /** 还没开始拉 */
    data object Idle : TextureState

    /** 正在下载 */
    data object Loading : TextureState

    /** 已拿到纹理 */
    data class Ready(val image: androidx.compose.ui.graphics.ImageBitmap, val slim: Boolean = false) : TextureState

    /** 拉取失败。message 直接给用户看 */
    data class Failed(val message: String) : TextureState

    /** 该角色没有设置皮肤（服务端明确返回无材质），渲染默认形象 */
    data object Absent : TextureState
}
