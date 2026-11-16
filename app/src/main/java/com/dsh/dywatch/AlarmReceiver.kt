package com.dsh.dywatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 两种响法共用一个入口：
 * - 0 点闹钟：排明天 + 确保监控服务活着（监控每轮取数自带当日 0 点快照补采）
 * - 15 分钟看门狗：把被杀的监控服务拉起来
 * 停摆哨兵：监控开着却很久没有成功取数（比如被 ROM 杀死且拉活失败）必须喊人——
 * 不许出现「服务早就死了，用户打开 App 之前谁都不知道」的状态。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleNextMidnight(context)
        Scheduler.scheduleWatchdog(context)

        // 停摆哨兵:还有号在监控 + 超 30 分钟没有成功检测 → 喊人(2 小时内不重复喊)
        // lastOk==0（从未成功过）同样要喊：只要跑完过至少一轮（lastRound>0）就按超期处理，
        // 否则首装就撞上无签名页时，报警链整条哑掉还装活着。
        val anyMon = Store.get(context).accounts.any { WatchPrefs.isMonitored(context, it.secUid) }
        val lastOk = WatchPrefs.lastSuccess(context)
        val lastRound = WatchPrefs.lastRound(context)
        val now = System.currentTimeMillis()
        val overdue = if (lastOk > 0) now - lastOk > 30 * 60 * 1000L else lastRound > 0
        if (anyMon && overdue &&
            now - WatchPrefs.lastStallAlert(context) > 2 * 60 * 60 * 1000L
        ) {
            WatchPrefs.setLastStallAlert(context, now)
            LiveNotify.postWarn(
                context,
                "开播监控停摆",
                "开播监控开着，但已超 30 分钟没有成功检测，可能被系统杀掉了；" +
                    "打开 App 恢复，或进「体检」看哪一环断了",
                4004
            )
        }

        try {
            val i = Intent(context, LiveWatchService::class.java)
            // 0 点的闹钟带 ACTION_SNAPSHOT：服务只写当日快照，不发提醒
            if (intent.action == LiveWatchService.ACTION_SNAPSHOT) {
                i.setAction(LiveWatchService.ACTION_SNAPSHOT)
            }
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            // ROM 拦后台启动就算了，下次打开 App 会拉起；停摆哨兵会持续喊
        }
    }
}
