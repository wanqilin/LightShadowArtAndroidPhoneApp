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
    /** 边框线条颜色，0xAARRGGBB */
    val lineColor: Int = DEFAULT_LINE_COLOR,
    /** 单根线条宽度（dp） */
    val lineWidthDp: Float = DEFAULT_LINE_WIDTH_DP,
    /** 内外两条线框之间的间距（dp） */
    val lineGapDp: Float = DEFAULT_LINE_GAP_DP,
    /** 背景纸纯色，0xAARRGGBB */
    val paperColor: Int = DEFAULT_PAPER_COLOR,
    /** 背景纸开关：关 = 设备端回退到黑边高斯模糊 */
    val paperEnabled: Boolean = DEFAULT_PAPER_ENABLED,
    /**
     * 边框与图片的距离（dp），F-6 ①。
     * `null` = 本次不下发该字段、设备端沿用当前值（`POST /frame` 本来就支持部分更新）；
     * 从 `GET /frame` 解析出来的值一定非空。
     */
    val paddingDp: Float? = null,
    /** 边框到屏幕最外层的距离（dp），F-6 ②；语义同 [paddingDp] */
    val insetDp: Float? = null
) {
    companion object {
        /** 与设备端 `DeviceConfig.FRAME_LINE_COLOR` 一致（红） */
        const val DEFAULT_LINE_COLOR: Int = 0xFFD32F2F.toInt()
        /** 与设备端 `DeviceConfig.FRAME_LINE_WIDTH_DP` 一致 */
        const val DEFAULT_LINE_WIDTH_DP: Float = 2f
        /** 与设备端 `DeviceConfig.FRAME_LINE_GAP_DP` 一致 */
        const val DEFAULT_LINE_GAP_DP: Float = 12f
        /** 与设备端 `DeviceConfig.FRAME_PAPER_COLOR` 一致（米白） */
        const val DEFAULT_PAPER_COLOR: Int = 0xFFFDFBF7.toInt()
        /** 与设备端 `DeviceConfig.FRAME_PAPER_ENABLED` 一致 */
        const val DEFAULT_PAPER_ENABLED: Boolean = true
        /** 与设备端 `DeviceConfig.FRAME_PADDING_DP` 一致（F-6 ①） */
        const val DEFAULT_PADDING_DP: Float = 24f
        /** 与设备端 `DeviceConfig.FRAME_INSET_DP` 一致（F-6 ②） */
        const val DEFAULT_INSET_DP: Float = 24f
    }
}

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
