package com.dsh.dywatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 重启后把 0 点闹钟、看门狗和监控服务补回来（MIUI 需要给自启动权限才会收到这条） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Scheduler.scheduleNextMidnight(context)
            Scheduler.scheduleWatchdog(context)
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, LiveWatchService::class.java)
                )
            } catch (e: Exception) {
            }
        }
    }
}
