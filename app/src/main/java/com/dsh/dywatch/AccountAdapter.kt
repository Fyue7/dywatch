package com.dsh.dywatch

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主播卡片。
 *
 * 卡片上的开关只有「监控」和「加速」两个，它们只做一件事：盯开播、发提醒。
 * 粉丝数不在卡片上控制——它不自动取，只有右上角「刷新」按一下才取一次
 * （2026-11-16 用户定：直播监控与粉丝数记录完全不沾边）。
 */
class AccountAdapter(
    private val store: Store,
    private val onItemClick: (secUid: String) -> Unit,
    private val onSwitchChanged: () -> Unit = {}
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_name)
        val liveBadge: TextView = view.findViewById(R.id.tv_live_badge)
        val goLive: TextView = view.findViewById(R.id.btn_go_live)
        val monitor: Switch = view.findViewById(R.id.sw_monitor)
        val boost: Switch = view.findViewById(R.id.sw_boost)
        val follower: TextView = view.findViewById(R.id.tv_follower)
        val delta: TextView = view.findViewById(R.id.tv_delta)
        val meta: TextView = view.findViewById(R.id.tv_meta)
        val watchState: TextView = view.findViewById(R.id.tv_watch_state)
    }

    private val num = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun getItemCount(): Int = store.accounts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_account, parent, false)
        // 「去看直播」是 TextView 冒充的按钮：补按钮角色 + 名字，并把触摸目标扩到 48dp
        val goLive = v.findViewById<View>(R.id.btn_go_live)
        A11y.asButton(goLive, parent.context.getString(R.string.cd_go_live))
        A11y.expandTouch(goLive)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = store.accounts[position]
        val ctx = holder.itemView.context
        // 壁纸开时卡片走毛玻璃半透明白,关时实白
        (holder.itemView as com.google.android.material.card.MaterialCardView)
            .setCardBackgroundColor(UiWallpaper.cardColor(ctx))
        holder.itemView.setOnClickListener { onItemClick(a.secUid) }
        // 卡片本身也是可点的（进详情）：读屏器要能说出点进去会发生什么
        holder.itemView.contentDescription = "查看 " + a.nickname + " 详细数据"

        // 开关没有可见的名字就只是「开关」：按主播名生成，读屏器才分得清四个号
        holder.monitor.contentDescription = "开播监控 " + a.nickname
        holder.boost.contentDescription = "15 秒加速检测开播 " + a.nickname

        val monitored = WatchPrefs.isMonitored(ctx, a.secUid)
        val boosted = WatchPrefs.isBoosted(ctx, a.secUid)

        // 监控开关：开 = 自动检测开播并提醒；关 = 该号不检测、不提醒（粉丝数不受它影响）
        holder.monitor.setOnCheckedChangeListener(null)
        holder.monitor.isChecked = monitored
        holder.monitor.setOnCheckedChangeListener { _, on: Boolean ->
            WatchPrefs.setMonitored(ctx, a.secUid, on)
            notifyServiceStateChanged(ctx)
            onSwitchChanged()
        }

        // 加速开关：把检测频率从 60 秒提到 15 秒，开播后自动复位
        holder.boost.setOnCheckedChangeListener(null)
        holder.boost.isChecked = boosted
        holder.boost.isEnabled = monitored
        holder.boost.alpha = if (monitored) 1f else .5f
        holder.boost.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            WatchPrefs.setBoost(holder.itemView.context, a.secUid, on)
            onSwitchChanged()
        }

        holder.name.text = a.nickname + "  @" + a.uniqueId

        // ========== 两块信息，两个来源，互不引用 ==========
        // 监控域（是否在播 / 房间号 / 提醒）：只认检测轮写下的状态，绝不看样本
        // 数据域（粉丝数 / 涨跌 / 赞 / 上次取数时间）：只认刷新写下的样本，绝不算在播
        val (watchLive, watchTs) = WatchPrefs.liveState(ctx, a.secUid)
        val watchFresh = watchTs > 0 && System.currentTimeMillis() - watchTs <= 30 * 60 * 1000L
        val isLive = monitored && watchFresh && watchLive
        val liveRoom = if (isLive) WatchPrefs.liveRoom(ctx, a.secUid) else ""

        holder.liveBadge.visibility = if (isLive) View.VISIBLE else View.GONE
        holder.watchState.text = when {
            !monitored -> "监控未开 · 不检测开播"
            isLive -> "监控中 · 检测到正在直播" + (if (boosted) " · 15 秒加速" else "")
            watchFresh -> "监控中 · 当前未开播" + (if (boosted) " · 15 秒加速" else "")
            else -> "监控中 · 等待下一轮检测" + (if (boosted) " · 15 秒加速" else "")
        }
        holder.goLive.isEnabled = isLive
        holder.goLive.setOnClickListener { if (isLive) DeepLink.openLiveRoom(ctx, liveRoom) }

        val cur = store.latest(a.secUid)
        if (cur == null) {
            holder.follower.text = "—"
            holder.delta.text = "还没有取过数"
            holder.delta.setTextColor(0xFF665E80.toInt())
            holder.meta.text = "点右上「刷新」取一次"
            return
        }

        holder.follower.text = String.format(Locale.getDefault(), "%,d", cur.follower)

        // 涨跌对比对象 = 每天零点快照（用户指定）。今天的 0 点没采到就退回最近一天的。
        val pts = store.dailyList(a.secUid)
        val todayKey = Store.dateKeyOf(System.currentTimeMillis())
        val base = pts.lastOrNull { it.dateKey <= todayKey }
        if (base != null) {
            val d = cur.follower - base.follower
            val sign = if (d > 0) "+" else ""
            val baseText = String.format(Locale.getDefault(), "%,d", base.follower)
            holder.delta.text = if (base.dateKey == todayKey)
                "今日 $sign$d（0 点 $baseText）"
            else
                "较昨日 0 点 $sign$d（$baseText）"
            holder.delta.setTextColor(if (d >= 0) 0xFF10804A.toInt() else 0xFFD03050.toInt())
        } else {
            holder.delta.text = "还没有 0 点快照"
            holder.delta.setTextColor(0xFF665E80.toInt())
        }

        // 数据域只说数据：赞 + 上次取数时间（在播与否不写在这一行）
        holder.meta.text = "赞 " + String.format(Locale.getDefault(), "%,d", cur.likes) +
            " · 上次取数 " + num.format(Date(cur.ts))
    }

    /** 开关变了让服务重贴常驻通知（文案反映生效状态） */
    private fun notifyServiceStateChanged(ctx: Context) {
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, LiveWatchService::class.java)
                    .setAction(LiveWatchService.ACTION_STATE_CHANGED)
            )
        } catch (_: Exception) {
        }
    }
}
