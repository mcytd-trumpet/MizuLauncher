package com.mizulauncher.skin

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 皮肤纹理加载与校验。
 *
 * ## 为什么要校验
 * 用户上传的 PNG 经常不合规：尺寸不是 64×64、用了索引色、带了奇怪的颜色配置。
 * 直接画上去会得到「角色一半透明」或「整个变粉」（颜色通道被当成 alpha）这类现象。
 * 加载时就检查并给出明确结论，比让用户对着花屏猜要强。
 */
object SkinLoader {

    /** 校验结果 */
    sealed interface Validation {
        data class Valid(val width: Int, val height: Int, val legacy: Boolean) : Validation

        /** 尺寸不对：既不是 64×64 也不是 64×32 */
        data class BadSize(val width: Int, val height: Int) : Validation

        /** 根本不是图片 */
        data object NotAnImage : Validation
    }

    /**
     * 只读文件头判断尺寸，不解码整张图。
     *
     * 用于「用户选了一张 4K 的图当皮肤」这种场景：解码会瞬间吃掉几十 MB 内存，
     * 而读文件头只要几百字节。
     */
    fun validate(bytes: ByteArray): Validation {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return Validation.NotAnImage

        return when {
            w == SkinLayout.SIZE_MODERN && h == SkinLayout.SIZE_MODERN ->
                Validation.Valid(w, h, legacy = false)
            w == SkinLayout.SIZE_MODERN && h == SkinLayout.SIZE_LEGACY_HEIGHT ->
                Validation.Valid(w, h, legacy = true)
            // 旧版 64×32 有些工具会存成 32×32 的缩放版，也一并接受（渲染时按比例取）
            w == 32 && h == 32 -> Validation.Valid(w, h, legacy = true)
            else -> Validation.BadSize(w, h)
        }
    }

    /**
     * 解码成可绘制的位图。
     *
     * 关键参数：
     * - `inPreferredConfig = ARGB_8888`：皮肤必须带 alpha，用 RGB_565 会把透明处变成黑块；
     * - `inScaled = false`：禁止系统按屏幕密度缩放，否则取纹理坐标会全部错位。
     */
    fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inScaled = false
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()

    fun decodeOfSize(bytes: ByteArray, size: Int): ImageBitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inScaled = false
            inSampleSize = 1
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width == size && bmp.height == size) {
            bmp.asImageBitmap()
        } else {
            // 尺寸不标准时统一重采样到 64×64，保证投影取纹理时坐标一致
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, size, size, false)
            scaled.asImageBitmap()
        }
    }.getOrNull()
}
