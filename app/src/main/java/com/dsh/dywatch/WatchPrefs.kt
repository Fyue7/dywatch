package com.dsh.dywatch

import android.content.Context

/**
 * 监控相关的轻量开关与状态，SharedPreferences 存储。
 *
 * 两件事各管各的（2026-11-16 用户原则：直播监控与粉丝数记录不能沾边）：
 *  - 「监控」开关（isMonitored）：是否盯着这个主播的开播，自动轮询只为判定开播 + 报警。
 *  - 粉丝数：**不自动取**，只有按「刷新」才取一次；0 点快照由闹钟单独触发。
 * 「加速」只影响监控轮询的频率（60s → 15s），不是粉丝数的刷新频率。
 */
object WatchPrefs {

    const val BOOST_INTERVAL = 15L
    const val BASE_INTERVAL = 60L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun isBoosted(ctx: Context, secUid: String): Boolean =
        sp(ctx).getBoolean("boost_$secUid", false)

    fun setBoost(ctx: Context, secUid: String, on: Boolean) {
        sp(ctx).edit().putBoolean("boost_$secUid", on).apply()
    }

    // ===== 监控（开播检测）轮次：自动轮询的完成时间与最近一次成功 =====

    fun lastRound(ctx: Context): Long = sp(ctx).getLong("lastRound", 0)

    fun setLastRound(ctx: Context, ts: Long) {
        sp(ctx).edit().putLong("lastRound", ts).apply()
    }

    fun lastSuccess(ctx: Context): Long = sp(ctx).getLong("lastSuccess", 0)

    fun setLastSuccess(ctx: Context, ts: Long) {
        sp(ctx).edit().putLong("lastSuccess", ts).apply()
    }

    /** 最近一次开播检测的摘要（"检测 4 个 · 12:03:21"） */
    fun setDetectResult(ctx: Context, text: String) {
        sp(ctx).edit().putString("detectResult", text).apply()
    }

    fun detectResult(ctx: Context): String? = sp(ctx).getString("detectResult", null)

    // ===== 粉丝数：只有手点刷新（或 0 点快照）才会变 =====

    fun lastRefresh(ctx: Context): Long = sp(ctx).getLong("lastRefresh", 0)

    fun refreshResult(ctx: Context): String? = sp(ctx).getString("refreshResult", null)

    fun setRefresh(ctx: Context, text: String, ts: Long) {
        sp(ctx).edit().putString("refreshResult", text).putLong("lastRefresh", ts).apply()
    }

    // 停摆哨兵去重:上次发「监控停摆」通知的时间,2 小时内不重复喊
    fun lastStallAlert(ctx: Context): Long = sp(ctx).getLong("lastStallAlert", 0)

    fun setLastStallAlert(ctx: Context, ts: Long) {
        sp(ctx).edit().putLong("lastStallAlert", ts).apply()
    }

    // 提醒留痕：界面上能看到「上次开播提醒是什么时候」，也是漏报排查的证据
    fun setLastAlert(ctx: Context, name: String, ts: Long) {
        sp(ctx).edit().putString("lastAlertName", name).putLong("lastAlertTs", ts).apply()
    }

    fun lastAlertName(ctx: Context): String? = sp(ctx).getString("lastAlertName", null)

    fun lastAlertTs(ctx: Context): Long = sp(ctx).getLong("lastAlertTs", 0)

    // 提醒发出时对应的直播间 room_id_str(用于「去看直播」直达)
    fun setLastAlertRoom(ctx: Context, room: String) {
        sp(ctx).edit().putString("lastAlertRoom", room).apply()
    }

    fun lastAlertRoom(ctx: Context): String? = sp(ctx).getString("lastAlertRoom", null)

    /** 本地数据文件损坏等一次性提示（体检页/状态行会看到） */
    fun setResult(ctx: Context, text: String) {
        sp(ctx).edit().putString("lastResult", text).apply()
    }

    fun lastResult(ctx: Context): String? = sp(ctx).getString("lastResult", null)

    // 失败退避状态持久化:进程被杀重启后,风控冷却不归零
    fun failState(ctx: Context, secUid: String): Pair<Int, Long> =
        Pair(sp(ctx).getInt("fails_$secUid", 0), sp(ctx).getLong("nextcheck_$secUid", 0))

    fun setFailState(ctx: Context, secUid: String, fails: Int, nextCheck: Long) {
        sp(ctx).edit().putInt("fails_$secUid", fails).putLong("nextcheck_$secUid", nextCheck).apply()
    }

    fun clearFailState(ctx: Context, secUid: String) {
        sp(ctx).edit().remove("fails_$secUid").remove("nextcheck_$secUid").apply()
    }

    /* ===== 每主播「监控」开关：只管是否盯开播并提醒 ===== */

    fun isMonitored(ctx: Context, secUid: String): Boolean =
        sp(ctx).getBoolean("mon_$secUid", true)

    fun setMonitored(ctx: Context, secUid: String, on: Boolean) {
        sp(ctx).edit().putBoolean("mon_$secUid", on).apply()
    }

    /* ===== 自定义标题：左上角大字，用户可改，默认由各 flavor 的 string 决定 ===== */

    fun headerTitle(ctx: Context): String? = sp(ctx).getString("headerTitle", null)

    /** 传空字符串 = 恢复默认标题 */
    fun setHeaderTitle(ctx: Context, title: String) {
        val e = sp(ctx).edit()
        if (title.isBlank()) e.remove("headerTitle") else e.putString("headerTitle", title.trim())
        e.apply()
    }

    /* ===== 更新检查留痕：体检页显示，主界面状态行提示「有新版本」 ===== */

    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong("updateCheckedAt", 0)

    /** info = 体检页那行说明；newer = 发现的新版本名（空 = 已是最新/没查到） */
    fun setUpdateState(ctx: Context, info: String, newer: String, ts: Long) {
        sp(ctx).edit()
            .putString("updateInfo", info)
            .putString("updateNewer", newer)
            .putLong("updateCheckedAt", ts)
            .apply()
    }

    fun updateInfo(ctx: Context): String? = sp(ctx).getString("updateInfo", null)

    fun updateNewer(ctx: Context): String? =
        sp(ctx).getString("updateNewer", null)?.takeIf { it.isNotEmpty() }
}
