package com.watat.claudeswipe

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * キーボード表示中 (= タッチ探索モードを使えない間) に、判定範囲へ直接置く透明な受け口。
 * 右スワイプは自分で受け取り、タップは [onTap] に渡す (呼び出し側で Claude へ渡し直す)。
 * 縦方向などそれ以外の動きは受け口の中で捨てる。スクロールする内容の上には置かないこと。
 */
class SwipeCatcher(
    service: AccessibilityService,
    private val openDistancePx: Float,
    private val onRightSwipe: () -> Unit,
    private val onTap: (x: Float, y: Float) -> Unit,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = CatcherView(service)
    private val lp = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }
    private var attached = false

    fun show(zone: Rect) {
        lp.x = zone.left
        lp.y = zone.top
        lp.width = zone.width()
        lp.height = zone.height()
        if (attached) wm.updateViewLayout(view, lp) else {
            wm.addView(view, lp)
            attached = true
        }
    }

    fun hide() {
        if (attached) {
            wm.removeView(view)
            attached = false
        }
    }

    /** タップを下の Claude へ渡し直す間だけ、受け口をすり抜け可能にする */
    fun setPassThrough(passThrough: Boolean) {
        if (!attached) return
        lp.flags = if (passThrough) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(view, lp)
    }

    private inner class CatcherView(context: Context) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var claimed = false
        private var dropped = false
        private var opened = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val dx = e.rawX - downX
            val dy = e.rawY - downY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    claimed = false
                    dropped = false
                    opened = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!claimed && !dropped && hypot(dx, dy) > slop) {
                        if (dx > 0 && dx > abs(dy) * 1.5f) claimed = true else dropped = true
                    }
                    if (claimed && !opened && dx > openDistancePx) {
                        opened = true
                        onRightSwipe()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!claimed && !dropped) onTap(e.rawX, e.rawY)
                }
            }
            return true
        }
    }
}
