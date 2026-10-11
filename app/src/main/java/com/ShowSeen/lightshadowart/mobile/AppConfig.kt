package com.ShowSeen.lightshadowart.mobile

/**
 * 移动端集中配置（对应需求：移动与设备端各自单独一个文件存放版本号与设置参数）。
 *
 * 版本号修改时请同步 `app/build.gradle` 的 `versionName`；参数默认值供设备端未返回
 * `/auth`、`/config` 参数时回退，界面按「秒 / 分钟」展示、读写换算毫秒。
 */
object AppConfig {

    /** 移动端 App 版本号；修改时请同步 build.gradle 的 versionName */
    const val VERSION = "1.0"

    /** 开发公司（关于页版权信息） */
    const val COMPANY = "ShowSeen"

    /** 心跳间隔（毫秒）：设备端未下发参数时的缺省值 */
    const val DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L

    /** 会话超时时间（毫秒） */
    const val DEFAULT_SESSION_TIMEOUT_MS = 10 * 60_000L

    /** 无操作确认倒计时时长（毫秒） */
    const val DEFAULT_NO_OP_COUNTDOWN_MS = 60_000L

    /** 自动播放间隔时间（毫秒） */
    const val DEFAULT_AUTO_PLAY_INTERVAL_MS = 3 * 60_000L
}
