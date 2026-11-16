package com.dsh.dywatch

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 点主播卡片进来的二级页:当前粉丝数 + 每天 0 点快照的柱状图和列表 */
class DetailActivity : AppCompatActivity() {

    private lateinit var store: Store
    private var sec: String? = null

    private lateinit var title: TextView
    private lateinit var current: TextView
    private lateinit var tagToday: TextView
    private lateinit var tagYday: TextView
    private lateinit var hint: TextView
    private lateinit var back: TextView
    private lateinit var lblCur: TextView
    private lateinit var chart: BarChartView
    private lateinit var snapList: LinearLayout
    private lateinit var chartCard: com.google.android.material.card.MaterialCardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        EdgeUi.attach(this, findViewById(R.id.root_content))
        store = Store.get(this)
        sec = intent.getStringExtra("secUid")
        title = findViewById(R.id.tv_title)
        current = findViewById(R.id.tv_current)
        tagToday = findViewById(R.id.tv_tag_today)
        tagYday = findViewById(R.id.tv_tag_yday)
        hint = findViewById(R.id.tv_hint)
        back = findViewById(R.id.tv_back)
        lblCur = findViewById(R.id.tv_lbl_cur)
        chart = findViewById(R.id.chart)
        snapList = findViewById(R.id.snap_list)
        chartCard = findViewById(R.id.chart_card)
        back.setOnClickListener { finish() }
        // 「‹ 返回」是 TextView：补按钮角色 + 名称，触摸目标扩到 48dp
        A11y.asButton(back, getString(R.string.cd_back))
        A11y.expandTouch(back)
        findViewById<ImageView>(R.id.wallpaper).let { UiWallpaper.apply(it) }
        applyWallpaperChrome()
        populate()
    }

    override fun onResume() {
        super.onResume()
        // 监控服务在后台持续更新，回前台/停留时都刷一遍
        populate()
    }

    /** 壁纸开:内容底透明 + 卡片毛玻璃 + 自由文字反白(任意壁纸可读);关:全部素底 */
    private fun applyWallpaperChrome() {
        val on = UiWallpaper.isOn(this)
        findViewById<ImageView>(R.id.wallpaper).let { UiWallpaper.apply(it) }
        findViewById<View>(R.id.scrim).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scrim)
            .setBackgroundResource(R.drawable.bg_wallpaper_scrim)
        findViewById<View>(R.id.root_content)
            .setBackgroundColor(if (on) Color.TRANSPARENT else 0xFFF7F5FB.toInt())
        chartCard.setCardBackgroundColor(UiWallpaper.cardColor(this))
        val heroColor = if (on) 0xFFFFFFFF.toInt() else 0xFF221D38.toInt()
        val subColor = if (on) 0xFFE7E3F2.toInt() else 0xFF665E80.toInt()
        title.setTextColor(heroColor)
        current.setTextColor(heroColor)
        current.setShadowLayer(1.5f, 0f, 3f, 0xAA141020.toInt())
        back.setTextColor(subColor)
        lblCur.setTextColor(subColor)
        hint.setTextColor(subColor)
        EdgeUi.syncIcons(this, on)
    }

    private fun populate() {
        val secUid = sec ?: return finish()
        val acc = store.accounts.firstOrNull { it.secUid == secUid } ?: return finish()

        title.text = acc.nickname + "  @" + acc.uniqueId

        val cur = store.latest(secUid)
        current.text = if (cur == null) "—"
        else String.format(Locale.getDefault(), "%,d", cur.follower)

        val pts = store.dailyList(secUid)
        val todayKey = Store.dateKeyOf(System.currentTimeMillis())
        val fmtV = String.format(Locale.getDefault(), "%,d", cur?.follower ?: 0)

        // 双口径胶囊:今日(对比今天 0 点)/较昨日(对比昨天 0 点)
        val todaySnap = pts.lastOrNull { it.dateKey == todayKey }
        val ydaySnap = pts.lastOrNull { it.dateKey < todayKey }
        if (todaySnap != null && cur != null) {
            val d = cur.follower - todaySnap.follower
            tagToday.text = "今日 ${if (d > 0) "+" else ""}$d"
            tagToday.setTextColor(if (d >= 0) 0xFF10804A.toInt() else 0xFFD03050.toInt())
        } else {
            tagToday.text = "今日 —"
            tagToday.setTextColor(0xFF665E80.toInt())
        }
        if (ydaySnap != null && todaySnap != null) {
            val d = todaySnap.follower - ydaySnap.follower
            tagYday.text = "较昨日 ${if (d > 0) "+" else ""}$d"
            tagYday.setTextColor(if (d >= 0) 0xFF10804A.toInt() else 0xFFD03050.toInt())
        } else {
            tagYday.text = "较昨日 —"
            tagYday.setTextColor(0xFF665E80.toInt())
        }

        when {
            pts.size >= 2 -> {
                hint.visibility = View.GONE
                chart.visibility = View.VISIBLE
                chartCard.visibility = View.VISIBLE
                chart.setData(pts.map { it.follower }, pts.map { it.dateKey.substring(5) })
                // 自绘柱状图对读屏器就是一块空白：把首尾数值和期间变化念出来
                val first = pts.first()
                val last = pts.last()
                val diff = last.follower - first.follower
                chart.contentDescription = String.format(
                    Locale.getDefault(),
                    "近 %d 天粉丝快照柱状图。%s 是 %,d，%s 是 %,d，期间%s %,d",
                    pts.size, first.dateKey, first.follower, last.dateKey, last.follower,
                    if (diff >= 0) "增加" else "减少", Math.abs(diff)
                )
            }
            pts.size == 1 -> {
                hint.text = "已有 1 个 0 点快照，明天 0 点自动采下一个，柱子开始长出来"
                hint.visibility = View.VISIBLE
                chart.visibility = View.GONE
                chartCard.visibility = View.GONE
            }
            else -> {
                hint.text = "还没有 0 点快照，明天 0 点开始自动采集"
                hint.visibility = View.VISIBLE
                chart.visibility = View.GONE
                chartCard.visibility = View.GONE
            }
        }

        // 快照列表:最近 4 天,当日净变化一列
        snapList.removeAllViews()
        val recent = pts.takeLast(4)
        if (recent.isEmpty()) return
        val f = SimpleDateFormat("MM-dd", Locale.getDefault())
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            setCardBackgroundColor(UiWallpaper.cardColor(this@DetailActivity))
            radius = 16f * resources.displayMetrics.density
            strokeWidth = 1
            strokeColor = 0xFFECE8F4.toInt()
            elevation = 0f
        }
        val box = LinearLayout(this).apply { setPadding(
            (14 * resources.displayMetrics.density).toInt(), 0,
            (14 * resources.displayMetrics.density).toInt(), 0
        ) }
        box.orientation = LinearLayout.VERTICAL
        recent.forEachIndexed { idx, p ->
            val row = LinearLayout(this@DetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0,
                    (10 * resources.displayMetrics.density).toInt())
            }
            val isToday = p.dateKey == todayKey
            val dateLbl = TextView(this@DetailActivity).apply {
                text = f.format(Date(p.ts)) + (if (isToday) " · 今天" else "")
                textSize = 13f
                setTextColor(0xFF5A5470.toInt())
            }
            val valLbl = TextView(this@DetailActivity).apply {
                text = String.format(Locale.getDefault(), "%,d", p.follower)
                textSize = 14f
                setTextColor(0xFF221D38.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val prev = if (idx > 0) recent[idx - 1] else null
            val deltaLbl = TextView(this@DetailActivity).apply {
                if (prev != null) {
                    val d = p.follower - prev.follower
                    text = (if (d > 0) "+" else "") + d
                    setTextColor(if (d >= 0) 0xFF10804A.toInt() else 0xFFD03050.toInt())
                } else {
                    text = "0 点"
                    setTextColor(0xFF665E80.toInt())
                }
                textSize = 13f
                // 固定最小宽度+右对齐,和数值列拼成上下对齐的两列(修日期与数值挤在一起)
                minWidth = (52 * resources.displayMetrics.density).toInt()
                gravity = Gravity.END
            }
            row.addView(dateLbl)
            row.addView(valLbl)
            row.addView(deltaLbl)
            box.addView(row)
        }
        card.addView(box)
        snapList.addView(card)
    }
}
