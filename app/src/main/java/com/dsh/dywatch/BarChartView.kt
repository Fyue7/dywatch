package com.dsh.dywatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/** 极简柱状图：每天 0 点快照一根柱子。自带绘制，不引第三方库。数据点最多几十个（每天一个）。 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val values = ArrayList<Long>()
    private val labels = ArrayList<String>()

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B4C9E.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDD8EE.toInt()
        strokeWidth = dp(1f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF665E80.toInt()
        textSize = dp(11f)
    }
    private val lastTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF221D38.toInt()
        textSize = dp(12f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setData(vals: List<Long>, ls: List<String>) {
        values.clear()
        values.addAll(vals)
        labels.clear()
        labels.addAll(ls)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = dp(52f)
        val padR = dp(16f)
        val padT = dp(20f)
        val padB = dp(24f)

        val mn = values.minOrNull() ?: return
        val mx = values.maxOrNull() ?: return
        var lo: Double = mn.toDouble()
        var hi: Double = mx.toDouble()
        if (hi == lo) {
            hi += 1
            lo -= 1
        }
        val span = hi - lo
        lo -= span * 0.10
        hi += span * 0.10

        fun y(v: Double): Float =
            (padT + (1 - (v - lo) / (hi - lo)) * (h - padT - padB)).toFloat()

        // 每天一根柱子;柱宽最多 28dp,天数多了自动变窄,最少 4dp
        val band = (w - padL - padR) / n
        val barW = (band * 0.6f).coerceIn(dp(4f), dp(28f))
        val baseY = h - padB

        fun cx(i: Int): Float = padL + i * band + band / 2

        // 三条横网格线 + 左侧刻度；刻度精度跟着数据跨度走，跨度小就展开成精确数
        val step = (hi - lo) / 2
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val fy = y(v)
            c.drawLine(padL, fy, w - padR, fy, grid)
            val label = axisFmt(v, step)
            c.drawText(label, padL - dp(6f) - txt.measureText(label), fy + dp(4f), txt)
        }

        // 柱子:圆角顶,数值映射柱高
        val radii = FloatArray(8)
        for (i in 0 until n) {
            val top = y(values[i].toDouble())
            val left = cx(i) - barW / 2
            val rect = RectF(left, top, left + barW, baseY)
            val r = minOf(dp(4f), barW / 2f, baseY - top)
            java.util.Arrays.fill(radii, 0f)
            radii[0] = r; radii[1] = r  // 只圆上面两角
            radii[2] = r; radii[3] = r
            val p = Path().apply { addRoundRect(rect, radii, Path.Direction.CW) }
            c.drawPath(p, bar)
        }

        // 最后一根柱子的数值标在上方
        val li = n - 1
        val lastLabel = pointFmt(values[li])
        c.drawText(
            lastLabel,
            cx(li) - lastTxt.measureText(lastLabel) / 2,
            y(values[li].toDouble()) - dp(10f),
            lastTxt
        )

        // 日期刻度：最多画 6 个，首尾必画
        if (labels.size == n) {
            val step = ((n + 5) / 6).coerceAtLeast(1)
            for (i in 0 until n) {
                if (i % step == 0 || i == n - 1) {
                    c.drawText(
                        labels[i],
                        cx(i) - txt.measureText(labels[i]) / 2,
                        h - dp(6f),
                        txt
                    )
                }
            }
        }
    }

    private fun pointFmt(v: Long): String =
        if (v >= 100000) String.format(Locale.getDefault(), "%.1f万", v / 10000.0)
        else String.format(Locale.getDefault(), "%,d", v)

    /** 网格刻度：跨度决定小数位，跨度太小（日涨跌几十个）就直接用精确数 */
    private fun axisFmt(v: Double, step: Double): String =
        when {
            step >= 10000 -> String.format(Locale.getDefault(), "%.0f万", v / 10000.0)
            step >= 1000 -> String.format(Locale.getDefault(), "%.1f万", v / 10000.0)
            step >= 100 -> String.format(Locale.getDefault(), "%.2f万", v / 10000.0)
            else -> String.format(Locale.getDefault(), "%,d", Math.round(v))
        }
}
