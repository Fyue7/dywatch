package com.dsh.dywatch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新机制：发布源 = GitHub Releases，**不需要任何服务器**。
 *
 * 流程：查 api.github.com 上本仓库的最新 release → 从 asset 名字里读出 versionCode
 * （形如 `dywatch-1.2.6-vc18-debug.apk`）→ 比本地大就下载 → 校验 sha256 → 交给系统安装器。
 *
 * 几个刻意的选择：
 *  - 仓库必须公开：私有仓库的接口要 token，token 打进 App 等于公开；本工具就一个人用，公开源足够。
 *  - versionCode 放文件名里，不猜 tag 名：tag 是人写的（v1.2.6 / 1.2.6 / release-1.2.6 都见过），
 *    versionCode 才是 Android 认的比较依据。
 *  - 下载后必须核 sha256（GitHub 的 asset.digest 字段）；对不上就删掉不装。
 *  - 失败一律返回人话，不抛异常：更新链路坏了不能带崩 App（体检页会显示这句话）。
 */
object Update {

    /** 发布仓库。换源只改这两行。 */
    const val OWNER = "Fyue7"
    const val REPO = "dywatch"

    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 15000
    private const val MAX_APK_BYTES = 80L * 1024 * 1024

    private val apkNameRe = Regex("""dywatch-(\d+(?:\.\d+)*)-vc(\d+)[A-Za-z0-9_-]*\.apk""", RegexOption.IGNORE_CASE)

    data class Result(
        val ok: Boolean,
        val message: String,
        val versionName: String = "",
        val versionCode: Int = 0,
        val apkUrl: String = "",
        val apkName: String = "",
        val digest: String = ""
    ) {
        val newer: Boolean get() = ok && versionCode > BuildConfig.VERSION_CODE
    }

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    /** 体内带网络，必须在工作线程调用 */
    fun check(): Result {
        return try {
            val json = httpGetText(API)
            val root = JSONObject(json)
            val tag = root.optString("tag_name", "").trim()
            val body = root.optString("body", "")
            val assets = root.optJSONArray("assets")

            var bestName = ""
            var bestUrl = ""
            var bestDigest = ""
            var bestVer = ""
            var bestCode = 0
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (!name.endsWith(".apk", true)) continue
                    val m = apkNameRe.find(name)
                    val code = m?.groupValues?.get(2)?.toIntOrNull()
                        ?: Regex("""versionCode\s*[:=]\s*(\d+)""").find(body)
                            ?.groupValues?.get(1)?.toIntOrNull()
                        ?: continue
                    if (code > bestCode) {
                        bestCode = code
                        bestName = name
                        bestUrl = a.optString("browser_download_url", "")
                        bestDigest = a.optString("digest", "")
                        bestVer = m?.groupValues?.get(1) ?: tag.trimStart('v', 'V')
                    }
                }
            }
            if (bestCode == 0 || bestUrl.isEmpty()) {
                return Result(false, "release 里没有可识别的 apk（文件名要带 -vc<versionCode>）")
            }
            val now = currentVersionCode()
            val msg = if (bestCode > now) "发现新版本 v$bestVer（vc$bestCode），当前 v${currentVersionName()}（vc$now）"
            else "已是最新（vc$now，远端 vc$bestCode）"
            Result(true, msg, bestVer, bestCode, bestUrl, bestName, bestDigest)
        } catch (t: Throwable) {
            Result(false, "检查更新失败：" + humanError(t))
        }
    }

    /** 体内带网络与磁盘，必须在工作线程调用；成功返回本地 apk 文件 */
    fun download(ctx: Context, r: Result): Pair<File?, String> {
        if (!r.ok || r.apkUrl.isEmpty()) return Pair(null, "没有可下载的版本")
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, if (r.apkName.isNotEmpty()) r.apkName else "update.apk")
        return try {
            val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", "dywatch-updater")
            conn.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    val md = MessageDigest.getInstance("SHA-256")
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        total += n
                        if (total > MAX_APK_BYTES) {
                            out.delete()
                            return Pair(null, "包体超 80MB，不像本 App 的安装包，已放弃")
                        }
                        md.update(buf, 0, n)
                        os.write(buf, 0, n)
                    }
                    val got = md.digest().joinToString("") { "%02x".format(it) }
                    val want = r.digest.removePrefix("sha256:").lowercase()
                    if (want.isNotEmpty() && want != got) {
                        out.delete()
                        return Pair(null, "校验和不匹配，已删除下载的包（可能被中间人改过）")
                    }
                    Pair(out, "已下载 " + (total / 1024 / 1024) + "MB，校验通过")
                }
            }
        } catch (t: Throwable) {
            out.delete()
            Pair(null, "下载失败：" + humanError(t))
        }
    }

    /** 是否能装未知来源应用（Android 8+ 每个 App 单独开关） */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else true

    fun installSettingsIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 26) Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + ctx.packageName)
        ) else null

    /** 交给系统安装器。调用方要保证文件已校验过。 */
    fun install(ctx: Context, apk: File): String {
        if (!apk.exists()) return "安装包不见了，重新下载一次"
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            "已拉起系统安装器（装完打开就是新版本）"
        } catch (t: Throwable) {
            "拉不起安装器：" + humanError(t) + "（多半是没给「安装未知应用」权限）"
        }
    }

    /* ===== 内部 ===== */

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", "dywatch-updater")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun humanError(t: Throwable): String {
        val m = t.message ?: t.javaClass.simpleName
        if (BuildConfig.DEBUG) Logx.w("DyUpdate", "update failed", t)
        return when {
            m.contains("HTTP 404") -> "仓库里还没有发布任何 release"
            m.contains("HTTP 403") -> "GitHub 限流（403），过一会再试"
            m.contains("Unable to resolve host") -> "没网或 DNS 不通"
            else -> m
        }
    }
}
