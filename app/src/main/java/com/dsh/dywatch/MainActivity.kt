package com.dsh.dywatch

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面只负责「看」和「触发」：轮询、取数、提醒全部在 LiveWatchService。
 * 没有自己的 WebView——全 App 只有一个签名 WebView（服务里的），杜绝两个签名器一好一坏的精分 bug。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var adapter: AccountAdapter
    private lateinit var status: TextView
    private lateinit var watchStatus: TextView
    private lateinit var titleView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val secUidRe = Regex("MS4wLjABAAAA[A-Za-z0-9_-]+")
    private var statusHoldUntil = 0L

    /** 手工反馈用:8 秒内不被服务刷新覆盖 */
    private fun setStatus(text: String) {
        status.text = text
        statusHoldUntil = System.currentTimeMillis() + 8000
    }

    // 自定义壁纸:系统照片选择器(PickVisualMedia,免权限;GetContent 在 HyperOS 上回传会崩框架)
    private val pickWallpaper =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null && UiWallpaper.setFromUri(this, uri)) applyWallpaper()
            else if (uri != null) status.text = "壁纸设置失败：图片读取出错，换一张试试"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        EdgeUi.attach(this, findViewById(R.id.root_content))

        // 0 点后台采集要用前台服务通知，13+ 不给权限服务照跑、只是通知看不见
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        Scheduler.scheduleNextMidnight(this)
        Scheduler.scheduleWatchdog(this)
        try {
            ContextCompat.startForegroundService(this, Intent(this, LiveWatchService::class.java))
        } catch (e: Exception) {
        }

        store = Store.get(this)
        status = findViewById(R.id.status)
        watchStatus = findViewById(R.id.tv_watch_status)
        // 左上角大字：可自定义（点一下改名），默认值由打包时的 string 决定
        titleView = findViewById(R.id.title)
        titleView.text = WatchPrefs.headerTitle(this) ?: getString(R.string.header_title_default)
        titleView.setOnClickListener { showRenameDialog() }
        A11y.asButton(titleView, "改名字")
        adapter = AccountAdapter(
            store,
            onItemClick = { sec ->
                startActivity(Intent(this, DetailActivity::class.java).putExtra("secUid", sec))
            },
            // 记数/提醒/加速任一开关变化：重算卡片与状态行（数据与提醒两套开关互不影响）
            onSwitchChanged = {
                adapter.notifyDataSetChanged()
                refreshStatusLine()
            }
        )

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // 刷新按钮只做一件事：取一次所有人的粉丝数并更新界面
        // 与「监控」开关完全无关——监控关着的号也照取（粉丝数靠手点，不自动轮询）
        findViewById<Button>(R.id.refresh).setOnClickListener {
            val before = WatchPrefs.lastRefresh(this)
            status.text = "取数中…"
            try {
                val i = Intent(this, LiveWatchService::class.java)
                    .setAction(LiveWatchService.ACTION_REFRESH)
                ContextCompat.startForegroundService(this, i)
            } catch (e: Exception) {
                status.text = "取数失败：监控服务没起来，重开 App 再试"
                return@setOnClickListener
            }
            // 20 秒没回话不再干挂「取数中…」——无响应本身就是故障，必须说出来
            ui.postDelayed({
                if (WatchPrefs.lastRefresh(this) == before) {
                    status.text = "刷新没拿到结果：服务 20 秒没回话（可能签名页面已挂）· 点「解验证」看看"
                }
            }, 20000)
        }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btn_captcha).setOnClickListener {
            startActivity(Intent(this, CaptchaActivity::class.java))
        }
        findViewById<Button>(R.id.btn_health).setOnClickListener {
            startActivity(Intent(this, HealthActivity::class.java))
        }
        findViewById<Button>(R.id.btn_wallpaper).setOnClickListener {
            val options = arrayOf("选择图片(相册)", "恢复默认背景")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("自定义壁纸")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> pickWallpaper.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                        1 -> {
                            UiWallpaper.clear(this)
                            applyWallpaper()
                        }
                    }
                }
                .show()
        }
        findViewById<Button>(R.id.btn_test_notify).apply {
            setOnClickListener {
                isEnabled = false
                status.text = "5 秒后弹测试提醒，现在可以锁屏"
                ui.postDelayed({
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    // 样例名用列表里第一个主播；没有主播就用占位名
                    val demoName = store.accounts.firstOrNull()?.nickname ?: "示例主播"
                    LiveNotify.post(
                        this@MainActivity,
                        demoName + " 开播了",
                        "样例通知 · 无铃声有短震 · " + time,
                        isDemo = true
                    )
                    if (AlertOverlay.canShow(this@MainActivity)) {
                        AlertOverlay.show(
                            this@MainActivity,
                            demoName + " 开播了",
                            "悬浮窗演示 · 盖在一切界面之上 · " + time
                        )
                        status.text = "测试提醒已发：悬浮窗 + 横幅"
                    } else {
                        status.text = "未授「显示在其他应用上层」，只发了横幅"
                    }
                    isEnabled = true
                }, 5000)
            }
            // 长按 = 走监控服务里真实的 fireAlert 链路自测（含提醒留痕）
            setOnLongClickListener {
                LiveWatchService.simulateAlert(this@MainActivity)
                status.text = "模拟开播提醒已发（真实链路），状态行可见提醒留痕"
                refreshStatusLine()
                true
            }
        }

        applyWallpaper()
        refreshStatusLine()
        maybeCheckUpdate()
    }

    /** 每天最多查一次更新（GitHub Releases，不装服务器）。查到新版本只落一个标记，
     *  状态行后半句和体检页会显示，不额外发通知——更新不是提醒链路的一部分，不该抢戏。 */
    private fun maybeCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - WatchPrefs.lastUpdateCheck(this) < 24 * 60 * 60 * 1000L) return
        Thread {
            val r = Update.check()
            WatchPrefs.setUpdateState(
                this, r.message,
                if (r.newer) r.versionName else "",
                System.currentTimeMillis()
            )
            runOnUiThread { refreshStatusLine() }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
        refreshStatusLine()
        // 状态行自己找节奏刷新：不依赖「有没有取数结果回来」。
        // 否则监控全关时没有任何网络活动，界面会一直停在「监控服务未运行（正在拉起…）」这类过期文案上
        ui.removeCallbacks(uiTick)
        ui.postDelayed(uiTick, 1500)
        LiveWatchService.onStoreUpdated = {
            runOnUiThread {
                adapter.notifyDataSetChanged()
                refreshStatusLine()
            }
        }
        LiveWatchService.resolveSink = { sec, nick, kw ->
            runOnUiThread { handleResolveResult(sec, nick, kw) }
        }
    }

    override fun onPause() {
        LiveWatchService.onStoreUpdated = null
        LiveWatchService.resolveSink = null
        ui.removeCallbacks(uiTick)
        super.onPause()
    }

    /** 界面在台上时每 10 秒重算一次状态行（只读 SharedPreferences，不产生任何请求） */
    private val uiTick = object : Runnable {
        override fun run() {
            refreshStatusLine()
            ui.postDelayed(this, 10_000)
        }
    }

    /* ===== 添加主播 ===== */

    private fun showAddDialog() {
        if (!store.canAdd()) {
            status.text = "最多 4 个主播，已达上限"
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "粘贴分享链接或抖音号"
            setSingleLine()
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("添加主播")
            .setView(input)
            .setPositiveButton("添加") { _, _ -> handleAddInput(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 点左上角大字改名字（存本地；留空恢复默认）。改完常驻通知标题也跟着变 */
    private fun showRenameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(titleView.text)
            setSingleLine()
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("改个名字")
            .setMessage("只改左上角显示的名字；留空恢复默认")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val t = input.text.toString().trim()
                WatchPrefs.setHeaderTitle(this, t)
                titleView.text = WatchPrefs.headerTitle(this)
                    ?: getString(R.string.header_title_default)
                try {
                    ContextCompat.startForegroundService(
                        this, Intent(this, LiveWatchService::class.java)
                            .setAction(LiveWatchService.ACTION_STATE_CHANGED)
                    )
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 输入分流:sec_uid 直达 > 链接解析 > 抖音号走服务签名搜索 */
    private fun handleAddInput(text: String) {
        if (text.isEmpty()) return
        secUidRe.find(text)?.let { addBySec(it.value, ""); return }
        if (text.contains("douyin.com")) {
            status.text = "解析链接中…"
            // 捕 Throwable：网络线程上的任何 Error（如 OOM）都不许带走整个进程
            Thread {
                val sec = try {
                    resolveLink(text)
                } catch (t: Throwable) {
                    null
                }
                runOnUiThread {
                    if (sec != null) addBySec(sec, "")
                    else status.text = "链接里没解析到主播，试试输入抖音号"
                }
            }.start()
            return
        }
        if (LiveWatchService.running) {
            status.text = "搜索「$text」中…"
            LiveWatchService.requestResolve(text)
        } else {
            status.text = "监控服务未运行，稍后再试"
        }
    }

    /** 只解析抖音/iesdouyin 域——门槛（文本含 douyin.com）和实际请求目标必须绑定 */
    private fun isDouyinHost(u: String): Boolean {
        val h = try {
            java.net.URL(u).host
        } catch (e: Exception) {
            return false
        }
        return h == "douyin.com" || h == "v.douyin.com" || h.endsWith(".douyin.com") ||
            h == "iesdouyin.com" || h.endsWith(".iesdouyin.com")
    }

    private fun addBySec(sec: String, nick: String) {
        if (store.containsSecUid(sec)) {
            setStatus("该主播已在列表中")
            return
        }
        if (!store.canAdd()) {
            setStatus("最多 4 个主播，已达上限")
            return
        }
        store.addAccount(sec, if (nick.isEmpty()) "新主播" else nick, "")
        store.save()
        adapter.notifyDataSetChanged()
        setStatus("已添加${if (nick.isNotEmpty()) " $nick" else ""}，监控取数后自动更新信息")
    }

    private fun handleResolveResult(sec: String, nick: String, keyword: String) {
        if (sec.isEmpty()) {
            setStatus("没搜到「$keyword」，试试粘贴该主播的分享链接")
            return
        }
        addBySec(sec, nick)
    }

    /** 短链/链接 → 跟随跳转 → 从目标 URL 或分享页里抠出 sec_uid。
     *  安全闸门：初始 URL 和每一跳 Location 都必须是抖音域；正文限长 2MB（防 gzip 炸弹撑爆内存）。 */
    private fun resolveLink(text: String): String? {
        try {
            val urlRe = Regex("https?://\\S+")
            var url = urlRe.find(text)?.value ?: return null
            if (!isDouyinHost(url)) return null
            val mobileUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
            var hops = 0
            while (hops < 4) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", mobileUa)
                conn.responseCode
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc != null) {
                    var target = loc
                    if (!target.startsWith("http")) target = "https://v.douyin.com$target"
                    secUidRe.find(target)?.let { return it.value }
                    if (!isDouyinHost(target)) return null
                    url = target
                    hops++
                    continue
                }
                // 无跳转:抓当前页 HTML 找 sec_uid（限长读取；关掉隐式跳转防绕过域名闸门）
                val c2 = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c2.instanceFollowRedirects = false
                c2.connectTimeout = 8000
                c2.readTimeout = 8000
                c2.setRequestProperty("User-Agent", mobileUa)
                val cap = 2 * 1024 * 1024
                val out = java.io.ByteArrayOutputStream()
                c2.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        total += n
                        if (total > cap) return null
                        out.write(buf, 0, n)
                    }
                }
                c2.disconnect()
                val body = out.toString("UTF-8")
                return secUidRe.find(body)?.value
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /** 状态行两行分开说：第一行=粉丝数（只有手点刷新才变），第二行=开播监控 */
    private fun refreshStatusLine() {
        val alertN = store.accounts.count { WatchPrefs.isMonitored(this, it.secUid) }
        // 手工反馈（添加主播/刷新）8 秒内不被服务刷新覆盖
        if (System.currentTimeMillis() < statusHoldUntil) return
        status.text = when {
            !LiveWatchService.running -> "监控服务未运行（正在拉起…）"
            store.accounts.isEmpty() -> "还没有主播：点右上「添加」，粘贴抖音分享链接"
            else -> WatchPrefs.refreshResult(this) ?: "粉丝数：点右上「刷新」取一次（不自动取）"
        }
        // 第二行只讲开播监控：几个号在盯、最近一次检测是什么时候
        val lastDetect = WatchPrefs.lastSuccess(this)
        val lastTry = WatchPrefs.lastRound(this)
        val base = when {
            !LiveWatchService.running -> "监控服务未运行（正在拉起…）"
            alertN == 0 -> "开播监控全关 · 没有号在盯直播"
            lastDetect == 0L && lastTry == 0L -> "监控 $alertN 个 · 等待第一轮检测"
            lastDetect == 0L -> "监控 $alertN 个 · 检测还没成功过 · 上次尝试 " +
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastTry))
            else -> "监控 $alertN 个 · 最近检测 " +
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastDetect))
        }
        val alertName = WatchPrefs.lastAlertName(this)
        val alertTs = WatchPrefs.lastAlertTs(this)
        val updNewer = WatchPrefs.updateNewer(this)
        val updHint = if (updNewer != null) " · 有新版本 v$updNewer（体检页装）" else ""
        watchStatus.text = if (alertName != null && alertTs > 0) {
            base + " · 上次提醒 " + alertName + " " +
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(alertTs)) + updHint
        } else base + updHint
    }

    /** 铺壁纸:有 → 压暗加深、头部文字反白(任意壁纸都可读);无 → 全部回素底 */
    private fun applyWallpaper() {
        val on = UiWallpaper.isOn(this)
        findViewById<ImageView>(R.id.wallpaper).let { UiWallpaper.apply(it) }
        findViewById<View>(R.id.scrim).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scrim)
            .setBackgroundResource(if (on) R.drawable.bg_wallpaper_scrim else R.drawable.bg_wallpaper_scrim)
        findViewById<View>(R.id.root_content)
            .setBackgroundColor(if (on) Color.TRANSPARENT else 0xFFF7F5FB.toInt())
        // 壁纸态:头部和状态文字反白 + 阴影,保证任意壁纸上都可读
        val tColor = if (on) 0xFFFFFFFF.toInt() else 0xFF221D38.toInt()
        val sColor = if (on) 0xFFE7E3F2.toInt() else 0xFF665E80.toInt()
        findViewById<TextView>(R.id.title).apply {
            setTextColor(tColor)
            setShadowLayer(1.5f, 0f, 2f, 0xCC141020.toInt())
        }
        findViewById<TextView>(R.id.tv_watch_title).apply {
            setTextColor(tColor)
            setShadowLayer(1.5f, 0f, 2f, 0xCC141020.toInt())
        }
        status.apply {
            setTextColor(if (on) 0xFFE7E3F2.toInt() else 0xFF665E80.toInt())
            setShadowLayer(1.5f, 0f, 2f, 0xCC141020.toInt())
        }
        watchStatus.apply {
            setTextColor(sColor)
            setShadowLayer(1.5f, 0f, 2f, 0xCC141020.toInt())
        }
        EdgeUi.syncIcons(this, on)
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
