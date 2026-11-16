package com.dsh.dywatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 通知统一入口：
 * - post():开播提醒（无铃声、短双震的横幅 + 锁屏全屏弹出）。通知保持素净（点通知本体开看板），
 *   「去看直播」入口放在主播卡片和开播弹窗上，不占通知栏。
 * - postWarn():异常喊人（连败/页面空转/停摆哨兵共用「监控异常」渠道）。
 * 样例阶段由测试按钮触发，正式版由 LiveWatchService 调用。
 */
object LiveNotify {

    // 通道 id 带 v2：通道属性建好后系统不允许改，要改行为就得换新 id
    const val CHANNEL_LIVE = "dywatch_live2"

    fun ensureChannel(ctx: Context) {
        // 通知渠道是 8.0（API 26）才有的概念；7.x 上直接发通知，别碰这些类（否则 NoClassDefFoundError）
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 无声音、短双震：能感觉到、不打扰
        val ch = NotificationChannel(CHANNEL_LIVE, "开播提醒", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 300, 250, 300)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
        nm.deleteNotificationChannel("dywatch_live")
    }

    fun post(
        ctx: Context,
        title: String,
        text: String,
        isDemo: Boolean,
        notifId: Int = 4001,
        room: String = ""
    ) {
        ensureChannel(ctx)
        // requestCode 按 notifId 派生：多账号同秒提醒时，各自的 PendingIntent 互不覆盖
        val openApp = PendingIntent.getActivity(
            ctx, notifId,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreen = PendingIntent.getActivity(
            ctx, notifId + 100000,
            Intent(ctx, LiveAlertActivity::class.java)
                .putExtra("title", title)
                .putExtra("text", text)
                .putExtra("demo", isDemo)
                .putExtra("room", room),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val bear = BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_notify)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, CHANNEL_LIVE)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
        }
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(bear)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setFullScreenIntent(fullScreen, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        // id 按调用方给：多账号同秒提醒时，各自的 PendingIntent 互不覆盖
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // 通知权限被撤时别把服务带崩：开播提醒发不出去，但监控本身要活着
            if (BuildConfig.DEBUG) android.util.Log.w("DyNotify", "notify denied", e)
        }
    }

    /** 异常喊人:连败 3/10/17…、页面连续空转、服务停摆时用。notifId 分开,三类告警互不顶掉 */
    fun postWarn(ctx: Context, title: String, text: String, notifId: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("dywatch_warn", "监控异常", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val bear = BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_notify)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, "dywatch_warn")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx).setPriority(Notification.PRIORITY_DEFAULT)
        }
        val n = builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(bear)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, n)
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) android.util.Log.w("DyNotify", "warn notify denied", e)
        }
    }
}
