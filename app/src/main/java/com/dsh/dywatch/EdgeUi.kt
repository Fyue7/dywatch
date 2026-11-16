package com.dsh.dywatch

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 沉浸式系统栏:状态栏/导航栏透明,壁纸顶到屏幕边缘。
 * 内容用 insets 自己让位;状态栏图标深浅跟随壁纸开关自动切换。
 */
object EdgeUi {

    fun attach(activity: Activity, content: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        val d = content.resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val base = (14 * d).toInt()
            v.setPadding(base, bars.top + base, base, bars.bottom + base)
            insets
        }
        syncIcons(activity, UiWallpaper.isOn(activity))
    }

    /** 无壁纸:浅底深图标;有壁纸:深底白图标 */
    fun syncIcons(activity: Activity, wallpaperOn: Boolean) {
        val wic = WindowCompat.getInsetsController(
            activity.window, activity.window.decorView
        )
        wic.isAppearanceLightStatusBars = !wallpaperOn
        wic.isAppearanceLightNavigationBars = !wallpaperOn
    }
}
