package com.dsh.dywatch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 跳转抖音直播间。
 *
 * 三级尝试(按可靠性排序):
 * 1. snssdk1128://live?room_id=  抖音自家 deep link scheme(1128 = 抖音在字节的 appId)
 * 2. https://webcast.amemv.com/webcast/room/reflow/entry/?room_id=
 *    抖音直播间的标准分享链接——手机上装了抖音会经 App Links 直接进直播间,
 *    没装则打开网页(网页上有打开抖音按钮)。这正是微信里点抖音直播分享链接的行为。
 * 3. 兜底:直接拉起抖音主程序。
 *
 * room_id 来自开播时 profile 接口的 room_id_str(精确字符串,见 Douyin.kt)。
 */
object DeepLink {

    private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"

    fun openLiveRoom(context: Context, roomIdStr: String): Boolean {
        if (roomIdStr.isNotEmpty()) {
            val links = listOf(
                "snssdk1128://live?room_id=$roomIdStr",
                "https://webcast.amemv.com/webcast/room/reflow/entry/?room_id=$roomIdStr&app_id=1128"
            )
            for (link in links) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    return true
                } catch (e: ActivityNotFoundException) {
                    // 该链接无人接手,试下一级
                } catch (e: SecurityException) {
                    // 某些 ROM 拦截,试下一级
                }
            }
        }
        return openDouyinApp(context)
    }

    /** 兜底:拉起抖音主程序 */
    fun openDouyinApp(context: Context): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(DOUYIN_PACKAGE)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
