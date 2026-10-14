package com.ShowSeen.lightshadowart.mobile.net

/**
 * 边框参数（需求 3.3.1 / T-13，几何按 F-6 扩展），与设备端 `network/FrameParams.kt` 一一对应。
 *
 * 真值以设备端 `GET /frame` 回执为准；这里的默认值只用于解析兜底，
 * 需与设备端 `DeviceConfig.FRAME_*` 保持一致（Constitution 第 9.5 节：两端默认参数同值）。
 */
data class FrameParams(
    /** 当前相框样式 id；空串表示沿用设备端默认样式 */
    val styleId: String = "",
    /**
     * 边框总开关（F-6 第三轮）：关 = 设备端不画边框且照片外回到黑边高斯模糊。
     * 设备端用独立字段而不是 `style_id = "none"` 表达它，因为「关」还要求背景跟着回退。
     */
    val frameEnabled: Boolean = DEFAULT_FRAME_ENABLED,
    /** 边框线条颜色，0xAARRGGBB */
    val lineColor: Int = DEFAULT_LINE_COLOR,
    /** 单根线条宽度（dp） */
    val lineWidthDp: Float = DEFAULT_LINE_WIDTH_DP,
    /** 背景纯色，0xAARRGGBB（照片外铺满整屏的底色） */
    val paperColor: Int = DEFAULT_PAPER_COLOR,
    /** 背景纯色开关：关 = 设备端该层回退到黑边高斯模糊 */
    val paperEnabled: Boolean = DEFAULT_PAPER_ENABLED,
    /** 背景是否用用户自定义图片（F-6 第三轮）：图片优先于纯色 */
    val backgroundImage: Boolean = DEFAULT_BACKGROUND_IMAGE,
    /** 设备端是否已存有用户自定义背景图（只读，用于背景色行的呈现与预览） */
    val hasBackground: Boolean = false,
    /**
     * 内边框与照片的距离（dp），F-6 ①。
     * `null` = 本次不下发该字段、设备端沿用当前值（`POST /frame` 本来就支持部分更新）；
     * 从 `GET /frame` 解析出来的值一定非空。
     */
    val paddingDp: Float? = null,
    /**
     * 内边框与外边框之间的间距（dp），F-6 ⑤；语义同 [paddingDp]。
     * 它合并了设备端原有的 `line_gap_dp`（双框间距，同一个量），协议里不再有旧字段。
     */
    val borderGapDp: Float? = null,
    /** 外边框到屏幕边缘的距离（dp），F-6 ②；语义同 [paddingDp] */
    val insetDp: Float? = null
) {
    companion object {
        /** 与设备端 `DeviceConfig.FRAME_ENABLED` 一致（编译期默认关） */
        const val DEFAULT_FRAME_ENABLED: Boolean = false
        /** 与设备端 `DeviceConfig.FRAME_LINE_COLOR` 一致（红） */
        const val DEFAULT_LINE_COLOR: Int = 0xFFD32F2F.toInt()
        /** 与设备端 `DeviceConfig.FRAME_LINE_WIDTH_DP` 一致 */
        const val DEFAULT_LINE_WIDTH_DP: Float = 2f
        /** 与设备端 `DeviceConfig.FRAME_PAPER_COLOR` 一致（米白） */
        const val DEFAULT_PAPER_COLOR: Int = 0xFFFDFBF7.toInt()
        /** 与设备端 `DeviceConfig.FRAME_PAPER_ENABLED` 一致 */
        const val DEFAULT_PAPER_ENABLED: Boolean = true
        /** 与设备端 `DeviceConfig.FRAME_BACKGROUND_IMAGE` 一致 */
        const val DEFAULT_BACKGROUND_IMAGE: Boolean = false
        /** 与设备端 `DeviceConfig.FRAME_PADDING_DP` 一致（F-6 ①） */
        const val DEFAULT_PADDING_DP: Float = 24f
        /** 与设备端 `DeviceConfig.FRAME_BORDER_GAP_DP` 一致（F-6 ⑤） */
        const val DEFAULT_BORDER_GAP_DP: Float = 8f
        /** 与设备端 `DeviceConfig.FRAME_INSET_DP` 一致（F-6 ②） */
        const val DEFAULT_INSET_DP: Float = 24f
    }
}

/**
 * `POST /frame` 的**部分更新**载荷：字段为 `null` 表示本次不下发、设备端沿用当前值。
 *
 * 为什么单独一个类而不是复用 [FrameParams]：`FrameParams` 是「一份完整的设备端状态」，
 * 每个字段都有值；而下发时**只该带用户真正改过的字段**——上一版「风格」页用
 * `FrameParams(styleId = "custom")` 下发，把没碰过的线条颜色 / 线宽 / 背景纸一起按默认值
 * 覆盖了设备端（真实缺陷）。类型上区分「完整状态」与「增量补丁」，这类错误就不可能再写出来。
 */
data class FramePatch(
    val styleId: String? = null,
    val frameEnabled: Boolean? = null,
    val lineColor: Int? = null,
    val lineWidthDp: Float? = null,
    val paperColor: Int? = null,
    val paperEnabled: Boolean? = null,
    val backgroundImage: Boolean? = null,
    val paddingDp: Float? = null,
    val borderGapDp: Float? = null,
    val insetDp: Float? = null
)

/**
 * 相框样式条目（id + 展示名）。
 *
 * 由设备端下发，移动端只负责展示——样式集合是设备端的事实，
 * 客户端硬编码样式名会在设备端新增样式时立刻过期。
 */
data class FrameStyleInfo(
    val id: String,
    val name: String
)

/** `GET /frame` 与 `POST /frame` 的回执：当前参数 + 设备端可选样式列表 */
data class FrameConfig(
    val params: FrameParams,
    val styles: List<FrameStyleInfo>
)
