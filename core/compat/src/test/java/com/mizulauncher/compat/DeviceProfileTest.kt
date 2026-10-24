package com.mizulauncher.compat

import android.app.ActivityManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备分级的纯逻辑测试。
 *
 * 这些断言锁住的是「老手机上的玄学问题」：
 * 明明写了动画却不播放、明明不支持却去调 API 31 的方法。
 * 把判断逻辑从 `Build.VERSION.SDK_INT` 的散落调用收敛到 [DeviceProfile] 后，
 * 就可以在没有真机的情况下验证每一档的行为。
 */
class DeviceProfileTest {

    private fun profile(
        sdk: Int,
        anim: Boolean = true,
        lowRam: Boolean = false,
        abi: String = "arm64-v8a"
    ) = DeviceProfile(
        sdkInt = sdk,
        manufacturer = "Test",
        model = "Device",
        abi = abi,
        animationsEnabled = anim,
        isLowRam = lowRam
    )

    // ---------------- API 分级 ----------------

    @Test
    fun `动态取色只在 Android 12 及以上可用`() {
        assertFalse(profile(26).supportsDynamicColor)
        assertFalse(profile(29).supportsDynamicColor)
        assertFalse(profile(30).supportsDynamicColor)
        assertTrue(profile(31).supportsDynamicColor)   // Android 12
        assertTrue(profile(35).supportsDynamicColor)
    }

    @Test
    fun `预测性返回手势与动画是两档`() {
        // Android 13：有手势回调，没有跟手动画
        assertTrue(profile(33).supportsPredictiveBackGesture)
        assertFalse(profile(33).supportsPredictiveBackAnimation)
        // Android 14：动画才真正跟手
        assertTrue(profile(34).supportsPredictiveBackAnimation)
        // Android 12 及以下：都没有
        assertFalse(profile(31).supportsPredictiveBackGesture)
    }

    @Test
    fun `系统级返回动画从 Android 15 开始`() {
        assertFalse(profile(34).supportsSystemBackAnimation)
        assertTrue(profile(35).supportsSystemBackAnimation)
    }

    @Test
    fun `三十二位 ABI 识别`() {
        assertTrue(profile(30, abi = "armeabi-v7a").is32Bit)
        assertTrue(profile(30, abi = "x86").is32Bit)
        assertFalse(profile(30, abi = "arm64-v8a").is32Bit)
        assertFalse(profile(30, abi = "x86_64").is32Bit)
    }

    // ---------------- 动效分级 ----------------

    @Test
    fun `用户关闭系统动画时任何机型都不做动效`() {
        // 这是无障碍底线：系统设置优先于设计偏好
        assertEquals(MotionLevel.None, profile(35, anim = false).motionLevel)
        assertEquals(MotionLevel.None, profile(26, anim = false, lowRam = true).motionLevel)
    }

    @Test
    fun `低内存设备降级但不关闭动效`() {
        // 关键：不是 None。旧版被吐槽"生硬"，关掉动画会更生硬
        assertEquals(MotionLevel.Reduced, profile(31, lowRam = true).motionLevel)
        assertEquals(MotionLevel.Reduced, profile(26, lowRam = true).motionLevel)
    }

    @Test
    fun `老系统自动降级`() {
        // Android 8/9 的 GPU 与刘海屏适配都弱，转场一律收敛
        assertEquals(MotionLevel.Reduced, profile(26).motionLevel)
        assertEquals(MotionLevel.Reduced, profile(28).motionLevel)
        // Android 10 起恢复完整动效
        assertEquals(MotionLevel.Full, profile(29).motionLevel)
        assertEquals(MotionLevel.Full, profile(35).motionLevel)
    }

    @Test
    fun `动效强度决定是否允许位移与弹簧`() {
        assertTrue(MotionLevel.Full.allowsTranslation)
        assertTrue(MotionLevel.Full.allowsSpring)
        // 降级档：保留淡入淡出，去掉位移和弹簧
        assertFalse(MotionLevel.Reduced.allowsTranslation)
        assertFalse(MotionLevel.Reduced.allowsSpring)
        assertFalse(MotionLevel.None.allowsTranslation)
    }

    // ---------------- 时长换算 ----------------

    @Test
    fun `时长随动效强度缩放`() {
        assertEquals(250, MotionSpec.duration(MotionSpec.Medium, MotionLevel.Full))
        assertEquals(150, MotionSpec.duration(MotionSpec.Medium, MotionLevel.Reduced))
        assertEquals(0, MotionSpec.duration(MotionSpec.Medium, MotionLevel.None))
        assertEquals(400, MotionSpec.duration(MotionSpec.Long, MotionLevel.Full))
        assertEquals(240, MotionSpec.duration(MotionSpec.Long, MotionLevel.Reduced))
    }

    @Test
    fun `位移距离在降级时显著缩短`() {
        assertEquals(24, MotionSpec.distance(24, MotionLevel.Full))
        // 低端机上 24dp 位移要重绘约 10% 屏幕，缩短到 8dp
        assertEquals(8, MotionSpec.distance(24, MotionLevel.Reduced))
        assertEquals(0, MotionSpec.distance(24, MotionLevel.None))
        // 极端小的基数也不能变成 0，否则"有一点点动"就退化成"完全没动"
        assertEquals(2, MotionSpec.distance(1, MotionLevel.Reduced))
    }

    @Test
    fun `三档时长常量符合 M3 规范`() {
        assertEquals(150, MotionSpec.Short)
        assertEquals(250, MotionSpec.Medium)
        assertEquals(400, MotionSpec.Long)
        // 必须递增，否则"短动效用时比长动效还长"这种低级错误会出现
        assertTrue(MotionSpec.Short < MotionSpec.Medium)
        assertTrue(MotionSpec.Medium < MotionSpec.Long)
    }
}
