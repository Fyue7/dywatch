package com.dsh.dywatch

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * adb 安装确认框那种「盖在一切之上」的大弹窗：
 * TYPE_APPLICATION_OVERLAY 悬浮窗 + 背景压暗层，居中卡片带按钮。
 * 需要「显示在其他应用上层」权限（Settings.canDrawOverlays）。
 * 开播提醒主路径之一：亮屏使用中时盖脸提醒；锁屏时走 LiveAlertActivity 全屏页。
 * 「去看直播」= 深链直达直播间；演示/无 room_id 时兜底拉起抖音主程序，最后才是看板。
 */
object AlertOverlay {

    private var wm: WindowManager? = null
    private var dim: View? = null
    private var card: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private var autoDismiss: Runnable? = null

    fun canShow(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun show(context: Context, title: String, text: String, roomId: String = "") {
        android.util.Log.d("DyOverlay", "show called, canDrawOverlays=" + canShow(context))
        if (!canShow(context)) return
        val app = context.applicationContext
        dismiss()
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // 背景压暗层：点空白处关闭
        val dimV = View(app).apply { setBackgroundColor(0x66000000) }
        val dimP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        dimV.setOnClickListener { dismiss() }

        // 居中卡片
        val cardV = LayoutInflater.from(app).inflate(R.layout.alert_overlay, null)
        cardV.findViewById<TextView>(R.id.ov_title).text = title
        cardV.findViewById<TextView>(R.id.ov_text).text = text
        cardV.findViewById<TextView>(R.id.ov_ok).setOnClickListener { dismiss() }
        cardV.findViewById<TextView>(R.id.ov_go).setOnClickListener {
            dismiss()
            if (roomId.isNotEmpty()) {
                DeepLink.openLiveRoom(app, roomId)
            } else if (!DeepLink.openDouyinApp(app)) {
                // 抖音都不在才退回看板
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        // 弹窗里两个「按钮」都是 TextView：补按钮角色 + 名称，触摸目标扩到 48dp
        cardV.findViewById<TextView>(R.id.ov_ok).let {
            A11y.asButton(it, app.getString(R.string.cd_alert_ok))
            A11y.expandTouch(it)
        }
        cardV.findViewById<TextView>(R.id.ov_go).let {
            A11y.asButton(it, app.getString(R.string.cd_alert_go))
            A11y.expandTouch(it)
        }
        val cardP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            manager.addView(dimV, dimP)
            manager.addView(cardV, cardP)
            wm = manager
            dim = dimV
            card = cardV
            android.util.Log.d("DyOverlay", "overlay added ok")
            val r = Runnable { dismiss() }
            autoDismiss = r
            ui.postDelayed(r, 30000)  // 30 秒没人理自动收掉
        } catch (e: Exception) {
            android.util.Log.e("DyOverlay", "addView failed", e)
            // 被 ROM 拦了就算了，横幅兜底
        }
    }

    fun dismiss() {
        autoDismiss?.let { ui.removeCallbacks(it) }
        autoDismiss = null
        try {
            dim?.let { wm?.removeView(it) }
            card?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        dim = null
        card = null
    }
}
