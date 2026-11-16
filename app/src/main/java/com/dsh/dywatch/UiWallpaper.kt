package com.dsh.dywatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader

import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.ImageView
import java.io.File

/**
 * 自定义壁纸:主看板 + 详情页的背景图。
 * 相册选图 → 按屏幕尺寸抽样解码 → 存本地 JPEG;应用时整图模糊(Android 12+),
 * 上面压一层深色 scrim,卡片走半透明白,文字保持深墨色。
 */
object UiWallpaper {

    private fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    fun isOn(ctx: Context): Boolean = file(ctx).exists()

    /** 从相册 URI 载入并落盘;成功返回 true。每个失败分支都有日志——静默失败排查过一次,别再来 */
    fun setFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
            val targetW = 1440
            val targetH = 3120
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val s1 = ctx.contentResolver.openInputStream(uri)
            if (s1 == null) {
                android.util.Log.e("DyWatch", "wp: bounds stream null")
                return false
            }
            s1.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) {
                android.util.Log.e("DyWatch", "wp: bounds unreadable (${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType})")
                return false
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW &&
                bounds.outHeight / (sample * 2) >= targetH
            ) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val s2 = ctx.contentResolver.openInputStream(uri)
            if (s2 == null) {
                android.util.Log.e("DyWatch", "wp: decode stream null")
                return false
            }
            val src = s2.use { BitmapFactory.decodeStream(it, null, opts) }
            if (src == null) {
                android.util.Log.e("DyWatch", "wp: decode null sample=$sample")
                return false
            }
            val out = file(ctx).outputStream()
            val compressed = out.use { src.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!compressed) {
                android.util.Log.e("DyWatch", "wp: compress returned false")
                return false
            }
            android.util.Log.d("DyWatch", "wp: saved " + file(ctx).length() + " bytes (" + src.width + "x" + src.height + ")")
            src.recycle()
            true
        } catch (e: Exception) {
            android.util.Log.e("DyWatch", "wp: set failed", e)
            false
        }
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** 把壁纸铺到 ImageView 上;没设置时隐藏该 ImageView */
    fun apply(imageView: ImageView) {
        val ctx = imageView.context
        if (!isOn(ctx)) {
            imageView.visibility = View.GONE
            return
        }
        val bmp = BitmapFactory.decodeFile(file(ctx).path) ?: run {
            imageView.visibility = View.GONE
            return
        }
        imageView.setImageBitmap(bmp)
        imageView.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 31) {
            imageView.setRenderEffect(
                RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP)
            )
        }
    }

    /** 毛玻璃卡片底色:有壁纸时 70% 半透明白(壁纸明显透出),无壁纸时实白 */
    fun cardColor(ctx: Context): Int =
        if (isOn(ctx)) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt()
}
