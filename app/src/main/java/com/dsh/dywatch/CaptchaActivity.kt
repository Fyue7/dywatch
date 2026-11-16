package com.dsh.dywatch

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * 可见的抖音页面：风控验证码弹出来时，人工过一次滑块。
 * 同一个 App 里所有 WebView 共享 cookie，这里解开后监控服务的隐藏 WebView 一并放行。
 */
class CaptchaActivity : AppCompatActivity() {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captcha)
        web = findViewById(R.id.captcha_web)
        Douyin.setup(web!!, null) { _, _ -> }
        findViewById<Button>(R.id.captcha_done).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        web?.stopLoading()
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
