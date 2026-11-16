package com.dsh.dywatch

import android.annotation.SuppressLint
import android.os.Handler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/** 从抖音接口解析出的一个主播档案 */
data class ParsedProfile(
    val secUid: String,
    val ok: Boolean,
    val err: String,
    val nickname: String,
    val uniqueId: String,
    val follower: Long,
    val following: Long,
    val likes: Long,
    val live: Int,
    val roomId: Long,
    val roomIdStr: String
)

/**
 * 取数逻辑与 WebView 配置。主界面和 0 点后台采集服务共用这一份，
 * 保证两边的签名、静音、拦截行为完全一致。
 */
object Douyin {

    // 桌面版 UA：抖音对 Android WebView 的 UA 更敏感，装成桌面 Chrome 更稳
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

    /** 视频流直接不下载：省流量，也断掉自动播放声音的源头（签名和接口都不走这些地址） */
    fun isMediaUrl(host: String?, path: String?): Boolean {
        if (host == null || path == null) return false
        return host.contains("douyinvod.com") ||
            path.contains("/aweme/v1/play") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m3u8")
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

    /**
     * 静音 + 暂停页面里所有媒体，并装一个常驻 MutationObserver。
     * 注意：抖音页面的 DOM 变动极其频繁（播放器跟我们 pause 互相触发），
     * 回调里直接扫全页会把渲染线程榨干、连 setTimeout 都饿死——
     * 必须用防抖把扫描频率封顶（≤3 次/秒），这是取数挂死事故的教训。
     */
    fun muteMedia(web: WebView?) {
        web?.evaluateJavascript(MUTE_JS, null)
    }

    private val MUTE_JS = """
        (function(){
          if (window.__dyMuteInstalled) return;
          window.__dyMuteInstalled = true;
          var mute = function(e){ try{ e.muted = true; e.pause(); }catch(_){} };
          var pending = null;
          var scan = function(){
            pending = null;
            try{ document.querySelectorAll('video,audio').forEach(mute); }catch(_){}
          };
          var schedule = function(){ if (pending) return; pending = setTimeout(scan, 300); };
          scan();
          try {
            var mo = new MutationObserver(schedule);
            mo.observe(document.documentElement, {
              childList: true, subtree: true,
              attributes: true, attributeFilter: ['muted','src']
            });
          } catch(_){}
        })();
    """.trimIndent()

    /** 抖音是 SPA，视频元素在加载后几秒才异步出现，头 20 秒内定时压一遍 */
    fun startMuteLoop(web: WebView, handler: Handler) {
        var n = 16
        val r = object : Runnable {
            override fun run() {
                if (n-- <= 0) return
                muteMedia(web)
                handler.postDelayed(this, 1200)
            }
        }
        handler.postDelayed(r, 1200)
    }

    /**
     * 配置一个「只借签名」的 WebView 并打开抖音首页；页面加载完成后回调。
     * bridge 必须在 loadUrl 之前注册——页面加载后注册，当前页拿不到 DyBridge。
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun setup(web: WebView, bridge: Any?, onPageDone: (WebView?, String?) -> Unit) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = UA
        web.settings.mediaPlaybackRequiresUserGesture = true
        if (bridge != null) web.addJavascriptInterface(bridge, "DyBridge")
        web.webViewClient = object : WebViewClient() {
            // 安全闸门：顶层导航只许 douyin.com。子框架不经过这里，
            // 真正的兜底在 ServiceBridge 收话时的源检查 + handleResult 的批次/账号校验。
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host ?: return true
                return !(host == "www.douyin.com" || host == "douyin.com" || host.endsWith(".douyin.com"))
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val u = request?.url ?: return null
                return if (isMediaUrl(u.host, u.path)) emptyResponse() else null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                muteMedia(view)
                onPageDone(view, url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                muteMedia(view)
            }
        }
        web.loadUrl("https://www.douyin.com/")
    }

    /**
     * 在抖音自家的页面里跑 JS：用页面自带的 window.byted_acrawler.frontierSign 签名，
     * 再 fetch 用户详情接口。三层超时保证 onResult 一定回来：
     * ① 单个请求 Promise.race 8 秒；② 整批 15 秒 deadline；
     * ③ 外层 20 秒兜底 finish（防渲染线程被拖死时定时器饿死，调用方另有 Kotlin 侧看门狗）。
     */
    fun fetchJs(secUids: List<String>): String {
        val ids = JSONArray()
        for (s in secUids) ids.put(s)
        return """
            (function(){
              var done = false;
              function finish(res){ if (done) return; done = true; DyBridge.onResult(res); }
              setTimeout(function(){ finish(JSON.stringify({err:'deadline'})); }, 20000);
              try {
                if (!window.byted_acrawler || !window.byted_acrawler.frontierSign) {
                  finish(JSON.stringify({err:'no-signer'}));
                  return;
                }
                var ids = $ids;
                var base = 'https://www.douyin.com/aweme/v1/web/user/profile/other/';
                var out = [];
                var deadline = Date.now() + 15000;
                (async function(){
                  for (var i = 0; i < ids.length; i++) {
                    var sec = ids[i];
                    var one = {sec: sec, ok: false};
                    var left = deadline - Date.now();
                    if (left <= 0) { one.err = 'Deadline'; out.push(one); continue; }
                    var txt = '';
                    try {
                      var qs = 'device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1080&screen_height=2340&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=135.0.0.0&browser_online=true&engine_name=Blink&engine_version=135.0.0.0&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8&platform=PC&downlink=0.55&effective_type=3g&round_trip_time=500&sec_user_id=' + sec + '&publish_video_strategy_type=2';
                      var s = window.byted_acrawler.frontierSign({url: base + '?' + qs});
                      var params = new URLSearchParams(s).toString();
                      var wait = Promise.race([
                        fetch(base + '?' + qs + '&' + params, {credentials:'include', headers:{'accept':'application/json, text/plain, */*'}}).then(function(r){ return r.text(); }),
                        new Promise(function(_, rej){ setTimeout(function(){ rej(new Error('Timeout')); }, Math.min(left, 8000)); })
                      ]);
                      txt = await wait;
                      var j = JSON.parse(txt);
                      var u = j.user || {};
                      one.ok = true;
                      one.nickname = u.nickname || '';
                      one.unique_id = u.unique_id || '';
                      one.follower = u.follower_count || 0;
                      one.following = u.following_count || 0;
                      one.likes = u.total_favorited || 0;
                      one.room_id = u.room_id || 0;
                      // room_id 超出 JS 安全整数:全文扫描取最长数字串(JSON.parse 已丢精度,文本没丢)
                      try {
                        var re = /"room_id(_str)?"\s*:\s*"?(\d{12,})"?/g;
                        var found = '';
                        var m3;
                        while ((m3 = re.exec(txt)) !== null) {
                          if (m3[2].length > found.length) found = m3[2];
                        }
                        if (found) one.room_id_str = found;
                        else {
                          var pos = txt.indexOf('room_id');
                          console.log('DYRID miss: ' + (pos >= 0 ? txt.slice(Math.max(0, pos - 60), pos + 120) : txt.slice(0, 200)));
                        }
                      } catch(_){}
                      one.live = u.live_status || 0;
                    } catch (e) {
                      one.err = String((e && e.message) || e);
                      try { console.log('DYFETCH_ERR ' + sec.slice(-6) + ' ' + one.err + ' body=' + txt.slice(0, 120)); } catch(_){}
                    }
                    out.push(one);
                  }
                  finish(JSON.stringify({res: out}));
                })();
              } catch (e) {
                finish(JSON.stringify({err:'js:' + e}));
              }
            })();
        """.trimIndent()
    }

    /**
     * 抖音号 → sec_uid 解析:在抖音自家页面里签名调用站内用户搜索接口。
     * 游客搜索偶尔撞验证码——失败时上层提示改用分享链接。
     * 结果经 DyBridge 回传:{"resolve":[{sec,nick,uid}...]}
     */
    fun resolveJs(keyword: String): String {
        val kw = keyword.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        return """
            (function(){
              var done=false;
              function finish(r){ if(done) return; done=true; DyBridge.onResult(r); }
              setTimeout(function(){ finish(JSON.stringify({err:'deadline'})); }, 15000);
              try{
                if (!window.byted_acrawler || !window.byted_acrawler.frontierSign) {
                  finish(JSON.stringify({err:'no-signer'})); return;
                }
                var kw = '$kw';
                var base='https://www.douyin.com/aweme/v1/web/discover/user/';
                var qs='device_platform=webapp&aid=6383&channel=channel_pc_web&search_channel=aweme_user_general&sort_type=0&publish_video_strategy_type=2&source=normal_search&query_correct_type=1&is_filter_search=0&from_group_id=&keyword='+encodeURIComponent(kw)+'&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1080&screen_height=2340&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=135.0.0.0&browser_online=true&engine_name=Blink&engine_version=135.0.0.0&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8&platform=PC&downlink=0.55&effective_type=3g&round_trip_time=500';
                var s=window.byted_acrawler.frontierSign({url:base+'?'+qs});
                var params=new URLSearchParams(s).toString();
                var wait=Promise.race([
                  fetch(base+'?'+qs+'&'+params,{credentials:'include',headers:{'accept':'application/json'}}).then(function(r){return r.text();}),
                  new Promise(function(_,rej){ setTimeout(function(){ rej(new Error('Timeout')); },8000); })
                ]);
                wait.then(function(txt){
                  var j=JSON.parse(txt);
                  var list=(j.user_list||[]).map(function(u){
                    var ui=u.user_info||u;
                    return {sec:ui.sec_uid||'', nick:ui.nickname||'', uid:ui.unique_id||ui.short_id||''};
                  });
                  finish(JSON.stringify({resolve:list}));
                }).catch(function(e){ finish(JSON.stringify({err:'search:'+e})); });
              }catch(e){ finish(JSON.stringify({err:'js:'+e})); }
            })();
        """.trimIndent()
    }

    /** 解析 JS 桥回传的 JSON；err 结构返回空列表 */    fun parse(json: String): List<ParsedProfile> {
        val out = ArrayList<ParsedProfile>()
        try {
            val root = JSONObject(json)
            if (root.has("err")) return out
            val arr = root.optJSONArray("res") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ParsedProfile(
                        secUid = o.optString("sec"),
                        ok = o.optBoolean("ok"),
                        err = o.optString("err"),
                        nickname = o.optString("nickname"),
                        uniqueId = o.optString("unique_id"),
                        follower = o.optLong("follower"),
                        following = o.optLong("following"),
                        likes = o.optLong("likes"),
                        live = o.optInt("live"),
                        roomId = o.optLong("room_id"),
                        roomIdStr = o.optString("room_id_str")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }
}
