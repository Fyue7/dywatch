package com.dsh.dywatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Account(
    val secUid: String,
    var uniqueId: String,
    var nickname: String
)

data class Sample(
    val secUid: String,
    val ts: Long,
    val follower: Long,
    val following: Long,
    val likes: Long,
    val live: Int,
    val roomId: Long,
    val roomIdStr: String = ""
)

/** 每天一个 0 点快照，画曲线用的数据点 */
data class DailyPoint(
    val dateKey: String,   // yyyy-MM-dd
    val ts: Long,          // 实际采样时间
    val follower: Long,
    val backfill: Boolean  // true = 错过 0 点后补采的
)

/**
 * 本地存储：两份 JSON 文件。
 * dywatch.json       主播清单 + 每次手动采样的历史
 * dywatch-daily.json 每天 0 点快照（画曲线用）
 * 不依赖数据库，换机时把两个文件拷走即可。
 */
class Store(context: Context) {

    // quarantine 需要在初始化之后仍能写状态通知，留一份应用上下文
    private val appContext: Context = context.applicationContext

    companion object {
        fun dateKeyOf(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

        // 进程内单例：主界面、详情页、监控服务共用一份数据，避免两个写方互相覆盖
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store =
            inst ?: synchronized(this) {
                inst ?: Store(ctx.applicationContext).also { inst = it }
            }
    }

    private val file = File(context.filesDir, "dywatch.json")
    private val dailyFile = File(context.filesDir, "dywatch-daily.json")

    val accounts = ArrayList<Account>()
    val samples = ArrayList<Sample>()

    // secUid -> (dateKey -> 快照)
    private val daily = LinkedHashMap<String, LinkedHashMap<String, DailyPoint>>()

    init {
        load()
        if (accounts.isEmpty()) seedFromAsset()
        loadDaily()
    }

    /**
     * 首启要不要预置主播，完全由打包时的 `assets/seed_accounts.json` 决定：
     *  - 公开版没有这个资产 → 空列表，用户自己「添加」；
     *  - 个人版有这份名单 → 保留原来的四个号。
     * 名单是个人数据，仓库里不存（见 .gitignore：app/src/personal/assets/）。
     */
    private fun seedFromAsset() {
        try {
            val text = appContext.assets.open("seed_accounts.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sec = o.optString("secUid")
                if (sec.isEmpty() || containsSecUid(sec)) continue
                accounts.add(
                    Account(sec, o.optString("uniqueId"), o.optString("nickname"))
                )
            }
            if (accounts.isNotEmpty()) save()
        } catch (e: Exception) {
            // 没有这个资产是正常情况（公开版从零开始），不是错误
            if (BuildConfig.DEBUG) Logx.d("DyWatch", "no seed asset: " + e.message)
        }
    }

    fun latest(secUid: String): Sample? {
        for (i in samples.indices.reversed()) {
            if (samples[i].secUid == secUid) return samples[i]
        }
        return null
    }

    fun previous(secUid: String): Sample? {
        var seen = 0
        for (i in samples.indices.reversed()) {
            if (samples[i].secUid == secUid) {
                seen++
                if (seen == 2) return samples[i]
            }
        }
        return null
    }

    @Synchronized
    fun record(
        secUid: String,
        nickname: String,
        uniqueId: String,
        follower: Long,
        following: Long,
        likes: Long,
        live: Int,
        roomId: Long,
        ts: Long,
        roomIdStr: String = ""
    ) {
        for (a in accounts) {
            if (a.secUid == secUid) {
                if (nickname.isNotEmpty()) a.nickname = nickname
                if (uniqueId.isNotEmpty()) a.uniqueId = uniqueId
            }
        }
        samples.add(Sample(secUid, ts, follower, following, likes, live, roomId, roomIdStr))
        while (samples.size > 4000) samples.removeAt(0)
    }

    fun hasDaily(secUid: String, dateKey: String): Boolean =
        daily[secUid]?.containsKey(dateKey) == true

    // 添加主播(上限 4 个,防重复)
    fun canAdd(): Boolean = accounts.size < 4

    fun containsSecUid(secUid: String): Boolean =
        accounts.any { it.secUid == secUid }

    @Synchronized
    fun addAccount(secUid: String, nickname: String, uniqueId: String) {
        accounts.add(Account(secUid, uniqueId, nickname))
    }

    fun needsDailyBackfill(): Boolean {
        val today = dateKeyOf(System.currentTimeMillis())
        return accounts.any { !hasDaily(it.secUid, today) }
    }

    fun putDaily(secUid: String, dateKey: String, ts: Long, follower: Long, backfill: Boolean) {
        val m = daily.getOrPut(secUid) { LinkedHashMap() }
        m[dateKey] = DailyPoint(dateKey, ts, follower, backfill)
        if (m.size > 400) m.remove(m.keys.first())
        saveDaily()  // 写穿:快照不落盘,进程重启后曲线就断了(评估反馈的高优先级项)
    }

    fun dailyList(secUid: String): List<DailyPoint> =
        daily[secUid]?.values?.sortedBy { it.dateKey } ?: emptyList()

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("accounts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                accounts.add(
                    Account(
                        o.getString("secUid"),
                        o.optString("uniqueId"),
                        o.optString("nickname")
                    )
                )
            }
            val s = root.optJSONArray("samples") ?: JSONArray()
            for (i in 0 until s.length()) {
                val o = s.getJSONObject(i)
                samples.add(
                    Sample(
                        o.getString("secUid"),
                        o.getLong("ts"),
                        o.getLong("follower"),
                        o.optLong("following"),
                        o.optLong("likes"),
                        o.optInt("live"),
                        o.optLong("roomId"),
                        o.optString("roomIdStr")
                    )
                )
            }
        } catch (e: Exception) {
            // 文件坏了：留底(.bak)可查可救，并让界面知道发生过——不再无声清盘
            quarantine(file, "dywatch.json")
        }
    }

    /* ===== 落盘：主线程只拍快照，序列化与写盘全在后台单线程 =====
     * 采样最多 4000 条，每轮取数都要重写成 JSON；在主线程做这件事会白卡一下（审计 F-CODE-3 / GA-105）。
     * 顺序由单线程保证：先排的先落，后到的快照覆盖前面的；排队期间再来的请求只标记 saveAgain，
     * 不再堆任务（避免轮询密集时任务堆积）。 */

    private val saver: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "dywatch-store-save").apply { isDaemon = true }
        }
    private val saveGuard = Any()
    private var saveQueued = false
    private var saveAgain = false

    /** 全量采样文件异步落盘。调用方（主线程/服务线程）只付一次引用复制 */
    fun save() {
        synchronized(saveGuard) {
            if (saveQueued) {
                // 已有一份在排队：它序列化时读的是最新快照，这次不重复排
                saveAgain = true
                return
            }
            saveQueued = true
        }
        enqueueSave()
    }

    private fun enqueueSave() {
        val accSnap: List<Account>
        val smpSnap: List<Sample>
        synchronized(this) {
            accSnap = ArrayList(accounts)
            smpSnap = ArrayList(samples)
        }
        saver.execute {
            try {
                writeSamples(accSnap, smpSnap)
            } finally {
                val again = synchronized(saveGuard) {
                    saveQueued = false
                    if (saveAgain) {
                        saveAgain = false
                        saveQueued = true
                        true
                    } else false
                }
                if (again) enqueueSave()
            }
        }
    }

    private fun writeSamples(accounts: List<Account>, samples: List<Sample>) {
        try {
            val root = JSONObject()
            val arr = JSONArray()
            for (a in accounts) {
                val o = JSONObject()
                o.put("secUid", a.secUid)
                o.put("uniqueId", a.uniqueId)
                o.put("nickname", a.nickname)
                arr.put(o)
            }
            root.put("accounts", arr)
            val s = JSONArray()
            for (x in samples) {
                val o = JSONObject()
                o.put("secUid", x.secUid)
                o.put("ts", x.ts)
                o.put("follower", x.follower)
                o.put("following", x.following)
                o.put("likes", x.likes)
                o.put("live", x.live)
                o.put("roomId", x.roomId)
                o.put("roomIdStr", x.roomIdStr)
                s.put(o)
            }
            root.put("samples", s)
            atomicWrite(file, root.toString())
        } catch (e: Exception) {
            // 存不下不崩，但要留声——磁盘满/权限问题不能无声丢数据
            Logx.w("DyWatch", "store save failed", e)
        }
    }

    private fun loadDaily() {
        if (!dailyFile.exists()) return
        try {
            val root = JSONObject(dailyFile.readText())
            for (key in root.keys()) {
                val m = LinkedHashMap<String, DailyPoint>()
                val obj = root.optJSONObject(key) ?: continue
                for (d in obj.keys()) {
                    val o = obj.getJSONObject(d)
                    m[d] = DailyPoint(d, o.getLong("ts"), o.getLong("follower"), o.optBoolean("backfill"))
                }
                daily[key] = m
            }
        } catch (e: Exception) {
            // 坏了：留底后重攒，不无声丢
            quarantine(dailyFile, "dywatch-daily.json")
        }
    }

    private fun saveDaily() {
        try {
            val root = JSONObject()
            for ((sec, m) in daily) {
                val obj = JSONObject()
                for ((d, p) in m) {
                    val o = JSONObject()
                    o.put("ts", p.ts)
                    o.put("follower", p.follower)
                    o.put("backfill", p.backfill)
                    obj.put(d, o)
                }
                root.put(sec, obj)
            }
            atomicWrite(dailyFile, root.toString())
        } catch (e: Exception) {
            Logx.w("DyWatch", "daily save failed", e)
        }
    }

    /** 原子写：同目录临时文件 + fsync + 原子改名——进程死在任意时刻都不会留下半截文件 */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        java.io.FileOutputStream(tmp).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Android/Linux 上 rename 原子替换已存在目标，走不到这里；兜底删了重试一次
            target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    /** 坏文件留底 + 界面可见的复位通知——历史还能从 .bak 找回，不再无声清盘 */
    private fun quarantine(bad: File, name: String) {
        try {
            bad.renameTo(File(bad.parentFile, "$name.bak." + System.currentTimeMillis()))
        } catch (_: Exception) {
        }
        Logx.e("DyWatch", "$name corrupt, quarantined as .bak; starting from seed")
        try {
            WatchPrefs.setDataIssue(
                appContext,
                "本地数据文件损坏已留底(.bak)，历史从种子重新开始 · " +
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            )
        } catch (_: Exception) {
        }
    }
}
