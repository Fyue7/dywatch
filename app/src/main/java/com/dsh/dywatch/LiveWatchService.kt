package com.dsh.dywatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开播监控前台服务：常驻轮询。
 * 默认每个主播 60 秒一轮；单独开了「加速」的主播 15 秒一轮，开播后自动复位。
 * live_status 0→1 跳变 → 静音横幅 + 悬浮大弹窗 +（锁屏时）全屏亮屏页。
 * 页面/JS 挂死由 25 秒看门狗 reload 自愈——连续空转必须喊人，不许静默空转装活着；
 * 服务被杀由 15 分钟闹钟拉起；太久没有成功取数由停摆哨兵喊人（AlarmReceiver 里）。
 */
class LiveWatchService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.dsh.dywatch.REFRESH"
        const val ACTION_SNAPSHOT = "com.dsh.dywatch.SNAPSHOT"
        const val ACTION_DETECT = "com.dsh.dywatch.DETECT"
        const val ACTION_STATE_CHANGED = "com.dsh.dywatch.STATE"
        var onStoreUpdated: (() -> Unit)? = null  // 主界面挂回调刷 UI
        var resolveSink: ((secUid: String, nickname: String, keyword: String) -> Unit)? = null
        var running = false

        // 体检页看签名 WebView 是否就绪(setup 回调/桥回话置 true,看门狗重载/销毁置 false)
        var pageReadyFlag = false

        // 抖音号解析入口(主界面调用,转发给运行中的服务实例)
        private var resolveHook: ((String) -> Unit)? = null
        fun requestResolve(keyword: String) = resolveHook?.invoke(keyword)

        /**
         * 模拟开播：走和真实检测完全相同的 fireAlert 链路（通知+悬浮窗+锁屏页+提醒留痕），
         * 用来在不等真开播的情况下验证提醒栈。
         */
        fun simulateAlert(ctx: Context) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            // 样例名用列表里第一个主播；一个都没有就用 App 名——绝不写死任何人的名字
            val name = Store.get(ctx).accounts.firstOrNull()?.nickname
                ?: ctx.getString(R.string.app_name)
            WatchPrefs.setLastAlert(ctx, "模拟开播", System.currentTimeMillis())
            // 清掉上次真实开播留下的 room_id,免得模拟提醒的「去看直播」跳进一个已经下播的旧房间
            WatchPrefs.setLastAlertRoom(ctx, "")
            LiveNotify.post(ctx, "模拟开播：$name", "真实提醒链路测试 · $time", isDemo = true)
            AlertOverlay.show(ctx, "模拟开播：$name", "全链路测试 · $time")
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var pageReady = false

    /** 一次取数的用途。三种用途的回写规则完全不同，见 handleResult。 */
    private enum class FetchKind { DETECT, REFRESH, SNAPSHOT }
    private var pendingAsked: List<String>? = null
    // 这一批取数是干什么的：三种用途分开处理，互不影响
    //  DETECT   监控自动轮询——只为判定开播、报警；不写样本、不写快照
    //  REFRESH  手点刷新——取所有人的粉丝数、写样本、更新界面；不报警
    //  SNAPSHOT 0 点闹钟——取所有人的粉丝数、只写当日快照；不报警、不写样本
    private var pendingKind: FetchKind = FetchKind.DETECT
    // 下一次 tick 的强制用途（REFRESH/SNAPSHOT 由界面或闹钟触发，做一轮后自动清空）
    private var forceMode: FetchKind? = null
    private var pendingResolve: String? = null
    private var stallCount = 0  // 连续「页面无响应被看门狗重载」的轮数
    private val lastCheck = HashMap<String, Long>()
    private val failCount = HashMap<String, Int>()
    // 直播状态边沿（提醒用），与样本库解耦：记数关掉时不写样本，但开播边沿照样判得准
    private val lastLiveState = HashMap<String, Boolean>()
    // 原生探测（不走 WebView）失败后的冷却：这段时间该号退回签名取数路径
    private val nativeBlockedUntil = HashMap<String, Long>()
    private val probePool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "dywatch-liveprobe").apply { isDaemon = true }
        }
    // 数据闸门拒收期的直播报警边沿（独立于冻结基线，详见 handleResult 的 50% 闸门注释）
    private val gatedLiveAlerted = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildFgNotification(): Notification {
        // 前台通知说的是数据侧状态：记数几个、提醒几个（两者独立）
        val alertN = Store.get(this).accounts.count { WatchPrefs.isMonitored(this, it.secUid) }
        val bear = BitmapFactory.decodeResource(resources, R.drawable.ic_notify)
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "dywatch_bg")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setContentTitle(WatchPrefs.headerTitle(this) ?: getString(R.string.header_title_default))
            .setContentText(
                if (alertN > 0) "开播监控 $alertN 个 · 60秒/轮（加速 15秒）· 粉丝数点「刷新」"
                else "开播提醒全关 · 粉丝数点「刷新」取"
            )
            .setSmallIcon(R.drawable.ic_notify)
            .setLargeIcon(bear)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("dywatch_bg", "后台采集", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(2002, buildFgNotification())
        web = WebView(this)
        resolveHook = { kw -> startResolve(kw) }
        // 失败退避状态从持久层恢复:进程被杀重启后,风控冷却不归零
        for (a in Store.get(this).accounts) {
            val (fails, next) = WatchPrefs.failState(this, a.secUid)
            if (fails > 0) failCount[a.secUid] = fails
            lastCheck[a.secUid] = next
        }
        Douyin.setup(web!!, ServiceBridge()) { _, _ ->
            Logx.d("DyWatch", "page ready")
            pageReady = true
            pageReadyFlag = true
            Douyin.startMuteLoop(web!!, ui)
            tick()
        }
        // 每 5 秒看一眼谁到点了；到点的账号拼成一批去取
        ui.postDelayed(tickRunner, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        when (intent?.action) {
            ACTION_REFRESH -> {
                // 「刷新」只做一件事：取一次所有人的粉丝数并更新界面
                // ——不看监控开关、不报警（2026-11-16 用户定：粉丝数与直播监控不沾边）
                lastCheck.clear()
                failCount.clear()
                forceMode = FetchKind.REFRESH
                ui.post { tick() }
            }
            ACTION_SNAPSHOT -> {
                // 0 点闹钟：取一次所有人，只写当日快照（不报警、不写样本）
                lastCheck.clear()
                failCount.clear()
                forceMode = FetchKind.SNAPSHOT
                ui.post { tick() }
            }
            ACTION_DETECT -> {
                // 体检页「立即检测一轮」：只催监控检测，不动粉丝数
                lastCheck.clear()
                ui.post { tick() }
            }
            ACTION_STATE_CHANGED -> startForeground(2002, buildFgNotification())
        }
        return START_STICKY
    }

    private val tickRunner = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 5000)
        }
    }

    private fun tick() {
        if (pendingAsked != null) return
        val store = Store.get(this)
        val mode = forceMode ?: FetchKind.DETECT
        if (BuildConfig.DEBUG) {
            Logx.d(
                "DyWatch",
                "tick: ready=$pageReady busy=${pendingAsked != null} kind=$mode"
            )
        }
        if (!pageReady) {
            // 手点刷新/0 点快照撞上页面没就绪：以前这里是静默 return，用户只看到「手动刷新中…」永远不动
            if (mode != FetchKind.DETECT) {
                note(mode, "签名页面还在准备，就绪后自动完成这次取数（一般几秒）")
                onStoreUpdated?.invoke()
            }
            return
        }
        val now = System.currentTimeMillis()
        var due: List<String> = when (mode) {
            // 监控自动轮询：只取「监控」开着的号，按各自间隔；结果只用于判定开播
            FetchKind.DETECT -> store.accounts.filter {
                if (!WatchPrefs.isMonitored(this, it.secUid)) return@filter false
                val itv = if (WatchPrefs.isBoosted(this, it.secUid))
                    WatchPrefs.BOOST_INTERVAL else WatchPrefs.BASE_INTERVAL
                now - (lastCheck[it.secUid] ?: 0) >= itv * 1000
            }.map { it.secUid }
            // 手点刷新 / 0 点快照：所有人，不看监控开关、不看间隔
            else -> store.accounts.map { it.secUid }
        }
        // 监控检测优先走「原生探测」：live.douyin.com/<抖音号> 页面里就有 roomId，不需要签名、不需要 WebView。
        // 探不通（网络/页面结构变了）的号在 10 分钟内退回签名取数路径。
        if (mode == FetchKind.DETECT && due.isNotEmpty()) {
            val accounts = store.accounts.filter { it.secUid in due }
            val nativeList = accounts.filter {
                it.uniqueId.isNotBlank() && now >= (nativeBlockedUntil[it.secUid] ?: 0L)
            }
            if (nativeList.isNotEmpty()) {
                nativeList.forEach { lastCheck[it.secUid] = now }  // 先占位，避免同一轮重复探测
                runNativeRound(nativeList)
            }
            due = accounts.filter { it !in nativeList }.map { it.secUid }
        }
        if (due.isEmpty()) {
            if (mode != FetchKind.DETECT) {
                forceMode = null
                note(
                    mode,
                    if (store.accounts.isEmpty()) "还没有主播，先点「添加」" else "清单里没有可取的号"
                )
                onStoreUpdated?.invoke()
            }
            return
        }
        forceMode = null
        pendingKind = mode
        Logx.d("DyWatch", "fetching ${due.size} accounts kind=$mode")
        pendingAsked = due
        // MIUI 允许滑掉前台服务通知：每轮重新贴一次，保证「常驻可见」
        startForeground(2002, buildFgNotification())
        web?.evaluateJavascript(Douyin.fetchJs(due), null)
        ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, 25000)
    }

    /** 取数过程中的说明：手点刷新的说明写进「刷新结果」（主界面第一行），其余写进一次性提示 */
    private fun note(mode: FetchKind, text: String) {
        if (mode == FetchKind.REFRESH) {
            WatchPrefs.setRefresh(this, text, WatchPrefs.lastRefresh(this))
        } else {
            WatchPrefs.setResult(this, text)
        }
    }

    private val watchdog = Runnable {        Logx.d("DyWatch", "watchdog fired")
        if (pendingAsked != null) {
            // JS 挂死（页面异常/被拖死）：重载页面自愈，这批账号等页面好了重试
            pendingAsked = null
            pageReady = false
            pageReadyFlag = false
            stallCount++
            WatchPrefs.setResult(
                this, "页面无响应，已自动重载（连续 $stallCount 次）· " +
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            )
            onStoreUpdated?.invoke()
            // 连续空转 = 提醒链路可能已经瞎了，必须喊人：第 2 次喊，之后每 +4 次再喊
            if (stallCount == 2 || (stallCount > 2 && (stallCount - 2) % 4 == 0)) {
                LiveNotify.postWarn(
                    this, "开播监控页面异常",
                    "签名页面连续 $stallCount 次无响应，已自动重载在自救；" +
                        "若一直响这条，打开 App 点「解验证」检查",
                    4003
                )
            }
            web?.reload()
        }
    }

    private fun handleResult(json: String) {
        // 非 JSON 消息直接忽略（日志挪到验证之后，且网页内容换行会被抹掉，不能伪造日志行）
        if (!json.startsWith("{")) return
        // 本批接受的 secUid：必须答这一批、且是监控清单里的账号——在途标记清掉前先锁存
        val accepted = (pendingAsked ?: emptyList()).toSet()
        // 这一批的用途：决定后面"写不写数字 / 报不报警"
        val kind = pendingKind
        // 桥回话 = 页面活着,空转计数清零；在途标记与看门狗【无条件】清——
        // 不许任何前缀的消息把 tick 永久卡死（v1.2.1 事故的另一半：条件清给 {"x":1} 留了卡死洞）
        stallCount = 0
        pageReadyFlag = true
        ui.removeCallbacks(watchdog)
        pendingAsked = null
        // 解析结果/解析失败都要送达，不能被取数守卫吞掉
        val pr = pendingResolve
        if (pr != null) {
            pendingResolve = null
            ui.removeCallbacks(resolveTimeout)
            if (json.startsWith("{\"resolve\"")) handleResolveResult(json, pr)
            else resolveSink?.invoke("", "", pr)
            return
        }
        if (!json.startsWith("{\"res") && !json.startsWith("{\"err")) return
        if (BuildConfig.DEBUG) {
            Logx.d("DyWatch", "result: " + json.take(150).replace("\n", " "))
        }
        val now = System.currentTimeMillis()
        val store = Store.get(this)
        val today = Store.dateKeyOf(now)
        var okN = 0
        var failN = 0
        for (p in Douyin.parse(json)) {
            // 安全闸门：结果必须答这一批、且是监控清单里的账号——网页伪造的陌生条目直接丢弃
            if (p.secUid !in accepted || store.accounts.none { it.secUid == p.secUid }) {
                if (BuildConfig.DEBUG) {
                    Logx.w("DyWatch", "drop unrequested entry " + p.secUid.take(8))
                }
                continue
            }
            val last = store.latest(p.secUid)
            // 漏报防线：旧样本不算「上次在播」——若上次采样已经隔了 30 分钟以上
            // （比如昨天下播前的样本），今天她刚开播必须照报，不能被旧状态压住
            val wasLive = if (last != null && now - last.ts <= 30 * 60 * 1000L) {
                last.live > 0 || last.roomId > 0
            } else false
            // 数据合理性防线：解析坏/暴跌的数据拒收，按失败计——
            // 抖音哪天改字段名，绝不能把 0 粉丝/未开播静默写进库污染曲线
            var good = p.ok
            var badNote = p.err
            if (good && p.follower <= 0L) {
                good = false
                badNote = "粉丝数解析为 0（接口结构可能变了）"
            }
            if (good && last != null && last.follower > 0 && p.follower < last.follower / 2) {
                good = false
                badNote = "单轮跌超 50%，数据可疑已拒收"
                // 直播状态与粉丝数无关，不许被数据闸门连坐（漏报防线优先）。
                // 边沿判定不能用 wasLive：拒收期里基线冻结，wasLive 老化出 30 分钟窗口后恒 false，
                // 会每轮重复响警报——用独立状态做 0→1 边沿，拒收期内每场直播只报一次，下播即复位
                val liveNow = p.live > 0 || p.roomId > 0
                if (liveNow) {
                    if (gatedLiveAlerted.add(p.secUid)) fireAlert(p)
                } else {
                    gatedLiveAlerted.remove(p.secUid)
                }
            }
            if (good) {
                val isLive = p.live > 0 || p.roomId > 0
                val prevLive = if (lastLiveState.containsKey(p.secUid)) lastLiveState[p.secUid]!! else wasLive
                lastLiveState[p.secUid] = isLive
                WatchPrefs.setLiveState(this, p.secUid, isLive, now)
                when (kind) {
                    // 监控轮询：只为判定开播，一个数字都不写
                    FetchKind.DETECT -> if (!prevLive && isLive) fireAlert(p)
                    // 手点刷新：写样本（更新粉丝数与卡片），当天缺快照就补一条；不报警
                    FetchKind.REFRESH -> {
                        store.record(
                            secUid = p.secUid,
                            nickname = p.nickname,
                            uniqueId = p.uniqueId,
                            follower = p.follower,
                            following = p.following,
                            likes = p.likes,
                            live = p.live,
                            roomId = p.roomId,
                            ts = now,
                            roomIdStr = p.roomIdStr
                        )
                        if (!store.hasDaily(p.secUid, today)) {
                            store.putDaily(p.secUid, today, now, p.follower, backfill = true)
                        }
                    }
                    // 0 点快照：只写当日快照；不写样本、不报警
                    FetchKind.SNAPSHOT -> if (!store.hasDaily(p.secUid, today)) {
                        store.putDaily(p.secUid, today, now, p.follower, backfill = false)
                    }
                }
                failCount.remove(p.secUid)
                gatedLiveAlerted.remove(p.secUid)  // 恢复正常采样后，清掉拒收期的报警边沿记录
                WatchPrefs.clearFailState(this, p.secUid)
                lastCheck[p.secUid] = now
                okN++
            } else {
                failN++
                // 失败退避：连败次数越多，下次尝试推得越远（+5/10/15 分钟，封顶 30 分钟），
                // 给抖音风控冷却时间，避免越失败越打越勤。状态持久化：进程重启后冷却不归零
                val fails = (failCount[p.secUid] ?: 0) + 1
                failCount[p.secUid] = fails
                val nextCheck = now + (fails * 300L * 1000L).coerceAtMost(30L * 60L * 1000L)
                lastCheck[p.secUid] = nextCheck
                WatchPrefs.setFailState(this, p.secUid, fails, nextCheck)
                // 漏报防线：静默失败 = 变相漏提醒。连败必须反复喊人（3 次喊，之后每 +7 次再喊），
                // 错过一条通知不能等于永远沉默
                if (fails == 3 || (fails > 3 && (fails - 3) % 7 == 0)) {
                    // 网页侧的原始错误码翻成人话再进通知（deadline/no-signer/js:...）
                    val note = when (badNote) {
                        "deadline" -> "取数超时"
                        "no-signer" -> "签名页面未就绪"
                        else -> badNote
                    }
                    LiveNotify.postWarn(
                        this, "开播监控异常",
                        "连续 $fails 次取数失败" +
                            (if (note.isNotEmpty()) "（$note）" else "（疑似风控）") +
                            "，现在可能漏提醒；打开 App 点「解验证」恢复",
                        4002
                    )
                }
            }
        }
        store.save()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
        // 三种用途各自记账，互不覆盖：监控看「最近检测」，粉丝数看「最近刷新」
        when (kind) {
            FetchKind.DETECT -> {
                // 本轮「完成」才写 lastRound；有成功才写 lastSuccess——空转轮次不许伪装成活着
                WatchPrefs.setLastRound(this, now)
                if (okN > 0) WatchPrefs.setLastSuccess(this, now)
                WatchPrefs.setDetectResult(
                    this,
                    "检测 $okN 个" + (if (failN > 0) "，失败 $failN 个" else "") + " · " + time
                )
            }
            FetchKind.REFRESH -> WatchPrefs.setRefresh(
                this,
                "已刷新 $okN 个" + (if (failN > 0) "，失败 $failN 个" else "") + " · " + time,
                now
            )
            FetchKind.SNAPSHOT -> WatchPrefs.setResult(
                this,
                "0 点快照已采 $okN 个" + (if (failN > 0) "，失败 $failN 个" else "") + " · " + time
            )
        }
        onStoreUpdated?.invoke()
    }

    /** 原生探测一轮：live.douyin.com/<抖音号> 页面里找 roomId，找到就是正在播（顺带拿到精确 room_id） */
    private fun runNativeRound(accounts: List<Account>) {
        probePool.execute {
            val results = accounts.map { a -> a.secUid to LiveProbe.probe(a.uniqueId) }
            ui.post {
                val now = System.currentTimeMillis()
                var okN = 0
                var failN = 0
                for ((secUid, st) in results) {
                    if (!st.ok) {
                        failN++
                        // 探测失败：10 分钟内该号退回签名取数路径，别一直打坏页面
                        nativeBlockedUntil[secUid] = now + 10 * 60 * 1000L
                        lastCheck.remove(secUid)
                        Logx.w("DyWatch", "native probe failed(" + secUid.take(8) + "): " + st.note)
                        continue
                    }
                    okN++
                    val prev = prevLive(secUid, now)
                    lastLiveState[secUid] = st.live
                    WatchPrefs.setLiveState(this, secUid, st.live, now)
                    if (st.live && !prev) {
                        // 探测结果自带精确 room_id，深链比签名结果还准
                        fireAlertBy(secUid, "", st.roomId)
                    }
                    lastCheck[secUid] = now
                }
                WatchPrefs.setLastRound(this, now)
                if (okN > 0) WatchPrefs.setLastSuccess(this, now)
                WatchPrefs.setDetectResult(
                    this,
                    "原生探测 " + okN + " 个" + (if (failN > 0) "，退回签名 " + failN + " 个" else "") +
                        " · " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                )
                if (BuildConfig.DEBUG) {
                    Logx.d("DyWatch", "native round ok=" + okN + " fail=" + failN)
                }
                onStoreUpdated?.invoke()
            }
        }
    }

    /** 上一次的直播状态：内存优先；进程刚重启时从持久层读（超过 30 分钟的旧状态视为未知 = 当作没在播） */
    private fun prevLive(secUid: String, now: Long): Boolean {
        lastLiveState[secUid]?.let { return it }
        val (v, ts) = WatchPrefs.liveState(this, secUid)
        return if (ts > 0 && now - ts <= 30 * 60 * 1000L) v else false
    }

    private fun fireAlert(p: ParsedProfile) = fireAlertBy(p.secUid, p.nickname, p.roomIdStr)

    private fun fireAlertBy(secUid: String, fallbackName: String, roomIdStr: String) {
        // 监控开关关掉的号：即使被「刷新」顺手取到了数据，也不许开播提醒（开关的含义就在这）
        if (!WatchPrefs.isMonitored(this, secUid)) return
        val store = Store.get(this)
        val name = store.accounts.firstOrNull { it.secUid == secUid }?.nickname
            ?: fallbackName.ifBlank { getString(R.string.app_name) }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        WatchPrefs.setBoost(this, secUid, false)  // 开播即复位加速开关
        WatchPrefs.setLastAlert(this, name, System.currentTimeMillis())
        WatchPrefs.setLastAlertRoom(this, roomIdStr)
        Logx.d("DyWatch", "ALERT fired: " + name + " room=" + roomIdStr)
        // 通知 id 用账号槽位号：稳定、≤4 个账号绝不碰撞；保留段（≤4100）与账号段（5000+）完全分离。
        val slot = store.accounts.indexOfFirst { it.secUid == secUid }
        val notifId = if (slot >= 0) 5000 + slot else 5000 + store.accounts.size
        LiveNotify.post(
            this, name + " 开播了", "刚刚开播 · " + time,
            isDemo = false, notifId = notifId, room = roomIdStr
        )
        AlertOverlay.show(this, name + " 开播了", "点「去看直播」直达直播间 · " + time, roomIdStr)
    }

    /** 抖音号解析:签名搜索接口,结果走同一个 JS 桥回来 */
    fun startResolve(keyword: String) {
        ui.post {
            if (!pageReady) { resolveSink?.invoke("", "", keyword); return@post }
            if (pendingAsked != null || pendingResolve != null) {
                // 忙:3 秒后重试,最多 5 次
                retryCount++
                if (retryCount > 5) { retryCount = 0; resolveSink?.invoke("", "", keyword); return@post }
                ui.postDelayed({ requestResolve(keyword) }, 3000)
                return@post
            }
            retryCount = 0
            pendingResolve = keyword
            web?.evaluateJavascript(Douyin.resolveJs(keyword), null)
            ui.removeCallbacks(resolveTimeout)
            ui.postDelayed(resolveTimeout, 16000)
        }
    }

    private var retryCount = 0

    private val resolveTimeout = Runnable {
        if (pendingResolve != null) { pendingResolve = null; resolveSink?.invoke("", "", "") }
    }

    private fun handleResolveResult(json: String, keyword: String) {
        try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("resolve") ?: JSONArray()
            var hitSec = ""
            var hitNick = ""
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i) ?: continue
                val uid = u.optString("uid")
                val sec = u.optString("sec")
                if (uid == keyword && sec.isNotEmpty()) { hitSec = sec; hitNick = u.optString("nick"); break }
            }
            if (hitSec.isEmpty() && arr.length() == 1) {
                // 唯一结果时放宽:uid 可能显示为 short_id
                val u = arr.optJSONObject(0)
                if (u != null && u.optString("sec").isNotEmpty()) { hitSec = u.optString("sec"); hitNick = u.optString("nick") }
            }
            if (hitSec.isEmpty()) {
                resolveSink?.invoke("", "", keyword)  // 未命中
            } else {
                resolveSink?.invoke(hitSec, hitNick, keyword)
            }
        } catch (e: Exception) {
            resolveSink?.invoke("", "", keyword)
        }
    }

    /** JS 桥：@JavascriptInterface 回调在后台线程，转回主线程再处理。
     *  安全闸门：顶层文档不在 douyin.com 就不收话（跨源 iframe 不经过这里，
     *  兜底在 handleResult 的批次/账号校验）。 */
    inner class ServiceBridge {
        @JavascriptInterface
        fun onResult(json: String?) {
            if (json == null) return
            ui.post {
                val host = try {
                    Uri.parse(web?.url ?: "").host ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (host != "www.douyin.com" && !host.endsWith(".douyin.com")) {
                    if (BuildConfig.DEBUG) {
                        Logx.w("DyWatch", "drop bridge message from non-douyin document: $host")
                    }
                    return@post
                }
                handleResult(json)
            }
        }
    }

    override fun onDestroy() {
        running = false
        pageReadyFlag = false
        onStoreUpdated = null
        resolveHook = null
        ui.removeCallbacksAndMessages(null)
        web?.stopLoading()
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
