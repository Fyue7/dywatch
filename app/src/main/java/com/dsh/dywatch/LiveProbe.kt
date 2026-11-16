package com.dsh.dywatch

import java.net.HttpURLConnection
import java.net.URL

/**
 * 原生开播探测：不走 WebView、不需要签名。
 *
 * 做法很朴素——`https://live.douyin.com/<抖音号>` 这个页面在「正在直播」时会把房间号直接写在 HTML 里
 * （`roomId="7708659458880523051"`），没开播就没有这个属性。2026-11-16 实测：
 *   - 4 个号里正在直播的那个有 roomId，另外三个都没有；
 *   - 全程只要一个普通 GET + 移动端 UA，没有任何签名参数。
 *
 * 意义：监控轮询（判断开播）从此可以完全不依赖那个签名 WebView。
 * 签名 WebView 只在「点刷新取精确粉丝数」时才需要——那一步的接口确实必须带签名。
 * 备用关系：探测失败（网络异常 / 页面结构变了）时，上层会退回原来的签名取数路径。
 */
object LiveProbe {

    data class State(
        val ok: Boolean,        // 这次探测本身成功没有（false = 需要退回签名路径）
        val live: Boolean,
        val roomId: String,
        val note: String = ""
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; 23116PN5BC) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 8000
    private const val MAX_BYTES = 600 * 1024

    private val roomIdRe = Regex("""roomId="(\d{6,})"""")
    // 抖音号只可能是字母/数字/下划线/点/横线；其它字符一律不拼进 URL（防止把请求指到别的路径）
    private val idRe = Regex("[A-Za-z0-9_.-]{1,64}")

    /** 阻塞方法，必须在工作线程调用 */
    fun probe(uniqueId: String): State {
        if (uniqueId.isBlank()) return State(false, false, "", "没有抖音号，无法原生探测")
        if (!idRe.matches(uniqueId)) return State(false, false, "", "抖音号含非法字符，退回签名路径")
        return try {
            val conn = URL("https://live.douyin.com/$uniqueId").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            try {
                val code = conn.responseCode
                if (code != 200) return State(false, false, "", "HTTP $code")
                val buf = StringBuilder()
                conn.inputStream.use { input ->
                    val bytes = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(bytes)
                        if (n == -1) break
                        total += n
                        if (total > MAX_BYTES) break
                        buf.append(String(bytes, 0, n, Charsets.UTF_8))
                    }
                }
                val html = buf.toString()
                // 页面正常但确实没在播：HTML 里没有 roomId（也可能整个页面结构变了，用体积兜一道）
                if (html.length < 2000) return State(false, false, "", "页面异常（${html.length} 字符）")
                val m = roomIdRe.find(html)
                if (m != null) {
                    State(true, true, m.groupValues[1])
                } else {
                    State(true, false, "")
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Logx.w("DyProbe", "probe failed: " + t.message)
            State(false, false, "", t.message ?: t.javaClass.simpleName)
        }
    }
}
