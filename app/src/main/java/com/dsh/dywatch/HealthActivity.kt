package com.dsh.dywatch

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 体检页：把「开播提醒能不能响」拆成一环一环自检，红叉就是提醒可能哑掉的地方。
 * 出问题不憋着——哪环断了直接看到，带修复入口（跳对应系统设置）。
 * 底部带版本/机型信息，用户截图这一页就能报障。
 */
class HealthActivity : AppCompatActivity() {

    private class Item(
        val dot: String,
        val title: String,
        val detail: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null
    )

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health)
        EdgeUi.attach(this, findViewById(R.id.root_content))
        findViewById<TextView>(R.id.tv_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_back).let {
            A11y.asButton(it, getString(R.string.cd_back))
            A11y.expandTouch(it)
        }
        list = findViewById(R.id.health_list)
        findViewById<Button>(R.id.btn_rerun).setOnClickListener { populate() }
        populate()
    }

    override fun onResume() {
        super.onResume()
        populate()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun populate() {
        list.removeAllViews()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pw = getSystemService(Context.POWER_SERVICE) as PowerManager
        val now = System.currentTimeMillis()

        /* ===== 权限层：缺一个提醒就哑一层 ===== */

        val notifOk = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        list.addView(row(Item(
            mark(notifOk), "通知权限",
            if (notifOk) "已允许，横幅/喊人通知出得来"
            else "被拒：开播横幅和异常喊人通知全都出不来",
            if (!notifOk) "去开启" else null,
            if (!notifOk) jumpAppNotif() else null
        )))

        val ch = if (Build.VERSION.SDK_INT >= 26) nm.getNotificationChannel(LiveNotify.CHANNEL_LIVE) else null
        val chOk = if (Build.VERSION.SDK_INT < 26) true
        else ch != null && ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
        val chFix: (() -> Unit)? = if (!chOk && Build.VERSION.SDK_INT >= 26) jumpChannel() else null
        list.addView(row(Item(
            mark(chOk), "「开播提醒」渠道",
            when {
                Build.VERSION.SDK_INT < 26 -> "系统 7.x 没有通知渠道，按通知优先级发"
                ch == null -> "渠道还没建（发过一次提醒就有了）"
                chOk -> "级别正常，横幅+短震会响"
                else -> "渠道级别被关到静音（常有 ROM 偷偷干这事），横幅出不来"
            },
            if (chFix != null) "去开启" else null, chFix
        )))

        val overlayOk = Settings.canDrawOverlays(this)
        list.addView(row(Item(
            mark(overlayOk), "悬浮窗权限",
            if (overlayOk) "已允许，开播大弹窗能盖在别的应用上"
            else "没给：锁屏外的大弹窗这一层不工作（横幅和锁屏页不受影响）",
            if (!overlayOk) "去开启" else null,
            if (!overlayOk) jumpOverlay() else null
        )))

        val battOk = pw.isIgnoringBatteryOptimizations(packageName)
        list.addView(row(Item(
            if (battOk) "✅" else "⚠️", "电池优化白名单",
            if (battOk) "已忽略电池优化，服务不容易被杀"
            else "未加白名单：国产 ROM 大概率杀后台（提醒延迟/停摆的头号原因）",
            if (!battOk) "去开启" else null,
            if (!battOk) jumpBattery() else null
        )))

        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val exactOk = am.canScheduleExactAlarms()
            list.addView(row(Item(
                if (exactOk) "✅" else "⚠️", "闹钟和提醒",
                if (exactOk) "已允许，0 点快照准点采"
                else "没给：0 点快照退化成 0 点后 10 分钟窗口，曲线不失真",
                if (!exactOk) "去开启" else null,
                if (!exactOk) jumpExactAlarm() else null
            )))
        }

        /* ===== 运行层：服务/取数/页面 ===== */

        list.addView(row(Item(
            mark(LiveWatchService.running), "监控服务",
            if (LiveWatchService.running) "前台服务运行中"
            else "没在跑：提醒全停（打开主界面会自动拉起）",
            if (!LiveWatchService.running) "拉起服务" else null,
            if (!LiveWatchService.running) { { startServiceNow() } } else null
        )))

        val lastOk = WatchPrefs.lastSuccess(this)
        val fresh = lastOk > 0 && now - lastOk < 5 * 60 * 1000L
        val stale = lastOk > 0 && now - lastOk < 30 * 60 * 1000L
        list.addView(row(Item(
            if (fresh) "✅" else if (stale) "⚠️" else "❌", "开播检测链路",
            when {
                lastOk == 0L -> "还没有成功检测过开播状态" +
                    (WatchPrefs.detectResult(this)?.let { " · $it" } ?: "")
                fresh -> "最近成功检测 " + fmt(lastOk)
                stale -> "最近成功检测 " + fmt(lastOk) + "（有点久了，留意）"
                else -> "超 30 分钟没有成功检测，开播提醒可能停摆了"
            },
            if (!fresh) "立即检测一轮" else null,
            if (!fresh) { { triggerDetect() } } else null
        )))

        val pageOk = LiveWatchService.running && LiveWatchService.pageReadyFlag
        list.addView(row(Item(
            if (!LiveWatchService.running) "⚠️" else mark(pageOk), "签名页面",
            when {
                !LiveWatchService.running -> "服务没跑，页面状态未知"
                pageOk -> "WebView 页面就绪，签名器可用（只服务粉丝数与 0 点快照，开播检测不走它）"
                else -> "页面未就绪（刚启动/正在重载自愈；持续这样 = 接口或页面出事了）"
            }
        )))

        /* ===== 数据/目标层：粉丝数与开播监控分开报 ===== */

        val store = Store.get(this)
        val alertN = store.accounts.count { WatchPrefs.isMonitored(this, it.secUid) }
        val lastRefresh = WatchPrefs.lastRefresh(this)
        list.addView(row(Item(
            "ℹ️", "粉丝数（手点刷新）",
            (WatchPrefs.refreshResult(this) ?: "还没有刷新过 · 点右边取一次") +
                (WatchPrefs.dataIssue(this)?.let { " · " + it } ?: ""),
            "刷新",
            { triggerRefresh() }
        )))
        list.addView(row(Item(
            if (alertN > 0) "✅" else "⚠️", "开播监控范围",
            if (alertN > 0) "$alertN/${store.accounts.size} 个号在盯开播，其余不盯"
            else "全关了：不会自动检测、也不会提醒（粉丝数不受影响）"
        )))

        val todayKey = Store.dateKeyOf(now)
        val snapN = store.accounts.count { store.hasDaily(it.secUid, todayKey) }
        list.addView(row(Item(
            if (snapN == store.accounts.size) "✅" else "⚠️", "今日 0 点快照",
            if (snapN == store.accounts.size) "${store.accounts.size} 个号今日快照都已采"
            else "已采 $snapN/${store.accounts.size} 号（0 点闹钟采；缺的手点刷新会补）" +
                (WatchPrefs.snapshotResult(this)?.let { " · " + it } ?: "")
        )))

        val alertName = WatchPrefs.lastAlertName(this)
        val alertTs = WatchPrefs.lastAlertTs(this)
        list.addView(row(Item(
            "ℹ️", "上次开播提醒",
            if (alertName != null && alertTs > 0) "$alertName · " + fmtFull(alertTs)
            else "还没有提醒记录（可以回主界面按「测试提醒」验一遍提醒栈）"
        )))

        val douyinOk = packageManager.getLaunchIntentForPackage("com.ss.android.ugc.aweme") != null
        list.addView(row(Item(
            mark(douyinOk), "抖音与深链",
            if (douyinOk) "已安装，「去看直播」可直达直播间"
            else "未安装：「去看直播」退化成网页/无效，提醒本身不受影响"
        )))

        // 更新：GitHub Releases 作为发布源，检查/下载/装都在这一行下面
        val upNewer = WatchPrefs.updateNewer(this)
        val upInfo = WatchPrefs.updateInfo(this)
        val upChecked = WatchPrefs.lastUpdateCheck(this)
        list.addView(row(Item(
            if (upNewer != null) "⬆️" else "ℹ️", "应用更新",
            when {
                upInfo == null ->
                    "还没检查过 · 当前 v" + Update.currentVersionName() +
                        "（vc" + Update.currentVersionCode() + "）"
                upChecked > 0 -> upInfo + " · 检查于 " + fmtFull(upChecked)
                else -> upInfo
            },
            "检查", { checkUpdate() }
        )))
        if (upNewer != null) {
            val canInstall = Update.canInstall(this)
            list.addView(row(Item(
                "⬆️", "安装 v$upNewer",
                if (canInstall) "下载并核对 sha256，然后交给系统安装器"
                else "系统还没允许本应用「安装未知应用」，先点右边去授权",
                if (canInstall) "下载并安装" else "去授权",
                if (canInstall) { { downloadAndInstall() } }
                else { { Update.installSettingsIntent(this)?.let { startActivity(it) } } }
            )))
        }

        // 底部环境信息：用户截图这一页就能报障
        val ver = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v" + pi.versionName + "(" + pi.versionCode + ")"
        } catch (e: Exception) { "?" }
        list.addView(row(Item(
            "ℹ️", "版本 / 设备",
            "$ver · Android " + Build.VERSION.RELEASE + " · " +
                Build.MANUFACTURER + " " + Build.MODEL +
                "（报障请整页截图）"
        )))
    }

    private fun mark(ok: Boolean): String = if (ok) "✅" else "❌"

    private fun fmt(ts: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun fmtFull(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun row(it: Item): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        r.addView(TextView(this).apply {
            text = it.dot
            textSize = 16f
            setPadding(0, 0, dp(12f), 0)
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = it.title
            textSize = 15f
            setTextColor(0xFF221D38.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = it.detail
            textSize = 12f
            setTextColor(0xFF665E80.toInt())
        })
        r.addView(box, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        if (it.action != null) {
            r.addView(TextView(this).apply {
                text = it.actionLabel ?: "修复"
                textSize = 12f
                setTextColor(-1)
                background = ContextCompat.getDrawable(context, R.drawable.bg_go_live)
                setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
                setOnClickListener { _ -> it.action?.invoke() }
                // 每个修复按钮长得一样：名字里带上这一项的标题，读屏器才分得清点的是哪个
                A11y.asButton(this, (it.actionLabel ?: "修复") + "：" + it.title)
                A11y.expandTouch(this)
            })
        }
        return r
    }

    /* ===== 更新入口 ===== */

    private fun checkUpdate() {
        android.widget.Toast.makeText(this, "正在查 GitHub Releases…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val r = Update.check()
            runOnUiThread {
                WatchPrefs.setUpdateState(
                    this, r.message,
                    if (r.newer) r.versionName else "",
                    System.currentTimeMillis()
                )
                populate()
            }
        }.start()
    }

    private fun downloadAndInstall() {
        android.widget.Toast.makeText(this, "开始下载更新包…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val r = Update.check()
            if (!r.ok || !r.newer) {
                runOnUiThread {
                    WatchPrefs.setUpdateState(this, r.message, "", System.currentTimeMillis())
                    populate()
                }
                return@Thread
            }
            val pair = Update.download(this, r)
            val file = pair.first
            val msg = pair.second
            runOnUiThread {
                val text = if (file == null) msg else msg + " · " + Update.install(this, file)
                WatchPrefs.setUpdateState(this, text, r.versionName, System.currentTimeMillis())
                populate()
            }
        }.start()
    }

    /* ===== 修复入口 ===== */

    private fun jumpAppNotif(): () -> Unit {
        return {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun jumpChannel(): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < 26) return null
        return {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, LiveNotify.CHANNEL_LIVE)
            )
        }
    }

    private fun jumpOverlay(): () -> Unit {
        return {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun jumpBattery(): () -> Unit {
        return {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun jumpExactAlarm(): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < 31) return null
        return {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun startServiceNow() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, LiveWatchService::class.java))
        } catch (e: Exception) {
        }
        populate()
    }

    private fun triggerRefresh() {
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, LiveWatchService::class.java)
                    .setAction(LiveWatchService.ACTION_REFRESH)
            )
            android.widget.Toast.makeText(
                this, "已触发刷新粉丝数，几秒后点「重新体检」看结果",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "服务没起来，先用「拉起服务」",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 立即检测一轮开播状态（与粉丝数刷新无关） */
    private fun triggerDetect() {
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, LiveWatchService::class.java)
                    .setAction(LiveWatchService.ACTION_DETECT)
            )
            android.widget.Toast.makeText(
                this, "已触发开播检测，几秒后点「重新体检」看结果",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "服务没起来，先用「拉起服务」",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
