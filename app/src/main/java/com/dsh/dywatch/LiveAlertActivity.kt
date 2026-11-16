package com.dsh.dywatch

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 锁屏/灭屏时由 fullScreenIntent 直接亮屏弹出的全屏页。
 * showWhenLocked：压在锁屏界面之上，不用解锁就能看到。
 */
class LiveAlertActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        setContentView(R.layout.activity_live_alert)

        findViewById<TextView>(R.id.alert_title).text =
            intent.getStringExtra("title") ?: "开播了"
        findViewById<TextView>(R.id.alert_text).text =
            intent.getStringExtra("text") ?: ""

        findViewById<Button>(R.id.alert_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.alert_go).setOnClickListener {
            // 房间号优先取本条提醒自带的 extra（两条提醒重叠时不会点错人），prefs 只做旧提醒兜底
            val room = intent.getStringExtra("room") ?: WatchPrefs.lastAlertRoom(this) ?: ""
            if (room.isNotEmpty()) DeepLink.openLiveRoom(this, room)
            else DeepLink.openDouyinApp(this)  // 演示/无 room_id:兜底拉起抖音主程序
            finish()
        }
    }
}
