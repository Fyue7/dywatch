package com.dsh.dywatch

/**
 * 日志统一出口。
 *
 * 规则（审计发现：各处直接打 android.util.Log，release 包里会把 secUid 前缀、room id、
 * 主播名这些能对上人身份的东西写进 logcat）：
 *  - `d`（调试细节）：只在 debug 包里打，release 一声不出
 *  - `w` / `e`（真出事了）：两个包都打——用户报障时要靠它，但调用方不许把完整 secUid 塞进来
 */
object Logx {

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) android.util.Log.w(tag, msg, t) else android.util.Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) android.util.Log.e(tag, msg, t) else android.util.Log.e(tag, msg)
    }
}
