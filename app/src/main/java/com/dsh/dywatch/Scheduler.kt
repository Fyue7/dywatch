package com.dsh.dywatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** 每天准点 0 点的采样闹钟 */
object Scheduler {

    fun scheduleNextMidnight(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val t = c.timeInMillis
        // 闹钟带上 ACTION_SNAPSHOT：0 点这一次取数只为写当日快照（与开播监控、粉丝数刷新互不相干）
        val pi = PendingIntent.getBroadcast(
            ctx, 1001,
            Intent(ctx, AlarmReceiver::class.java).setAction(LiveWatchService.ACTION_SNAPSHOT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // 没给「闹钟和提醒」权限：退化为 0 点后 10 分钟内的一个窗口，不影响日曲线
                am.setWindow(AlarmManager.RTC_WAKEUP, t, 10 * 60 * 1000L, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, t, 10 * 60 * 1000L, pi)
        }
    }

    /** 每 15 分钟确认监控服务活着，被杀就拉起 */
    fun scheduleWatchdog(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 1002,
            Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 15 * 60 * 1000L,
            15 * 60 * 1000L,
            pi
        )
    }
}
