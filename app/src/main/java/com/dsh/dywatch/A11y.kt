package com.dsh.dywatch

import android.graphics.Rect
import android.view.TouchDelegate
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍补丁。本项目有几处「用 TextView 冒充按钮」（去看直播、返回、提醒弹窗按钮、体检修复），
 * 视觉已经按真机一轮轮调好，不改成 MaterialButton（会带入 Material 内边距、尺寸和配色，返工风险大），
 * 只补语义，读屏器（TalkBack）看到的就是按钮：
 *
 *  - 角色：节点 className 报成 android.widget.Button，并标可点击
 *  - 名字：没写名字的按钮读屏器只会念「按钮」，这里补 contentDescription
 *  - 触摸目标：视觉尺寸不动，用 TouchDelegate 把可点区域扩到 48dp
 */
object A11y {

    private const val MIN_TOUCH_DP = 48

    /** 给 TextView 这类控件补「按钮」角色与名称 */
    fun asButton(v: View, label: String? = null) {
        v.isClickable = true
        v.isFocusable = true
        if (!label.isNullOrEmpty()) v.contentDescription = label
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.isClickable = true
            }
        }
    }

    /** 触摸目标扩到至少 48dp，视觉尺寸不变（父容器范围内生效） */
    fun expandTouch(v: View, minDp: Int = MIN_TOUCH_DP) {
        v.post {
            val parent = v.parent as? View ?: return@post
            val minPx = (minDp * v.resources.displayMetrics.density).toInt()
            val r = Rect()
            v.getHitRect(r)
            val dw = ((minPx - r.width()) / 2).coerceAtLeast(0)
            val dh = ((minPx - r.height()) / 2).coerceAtLeast(0)
            if (dw == 0 && dh == 0) return@post
            r.inset(-dw, -dh)
            parent.touchDelegate = TouchDelegate(r, v)
        }
    }

    /** 状态行这类会随时变化的文字：读屏器跟着念，但不打断正在读的内容 */
    fun liveRegion(v: View) {
        v.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
}
