package com.watat.claudeswipe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager

/** 判定範囲を薄く表示する、タッチを受けないオーバーレイ (試作の確認用) */
class ZoneOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = ZoneView(service)
    private var attached = false

    fun show(zone: Rect) {
        view.zone.set(zone)
        if (!attached) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
            wm.addView(view, lp)
            attached = true
        }
        view.invalidate()
    }

    fun hide() {
        if (attached) {
            wm.removeView(view)
            attached = false
        }
    }

    private class ZoneView(context: Context) : View(context) {
        val zone = Rect()
        private val fill = Paint().apply { color = Color.argb(28, 217, 119, 87) }
        private val stroke = Paint().apply {
            color = Color.argb(140, 217, 119, 87)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val loc = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            // ウィンドウ原点が画面原点とずれていても画面座標で描けるように補正する
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            canvas.drawRect(zone, fill)
            canvas.drawRect(zone, stroke)
            canvas.restore()
        }
    }
}
