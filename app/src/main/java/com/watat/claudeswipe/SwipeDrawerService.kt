package com.watat.claudeswipe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.TouchInteractionController
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Claude アプリの会話エリアで右スワイプを検出し、左上のメニューボタンを押してサイドバーを開く。
 *
 * タッチを「見るだけ」の API は signature 権限でしか使えないため、公開 API の
 * TouchInteractionController を使う。Claude が前面にある間だけタッチ探索モードに入り、
 * 判定範囲 (zone) の外はパススルー領域として OS に素通しさせる。
 * zone の中のタッチは一旦預かり、右スワイプなら自分で処理、それ以外は requestDelegating() で
 * アプリへ返す。指を離すまで判定がつかなかったタップは dispatchGesture で作り直す。
 *
 * キーボードはタッチ探索モードが有効だと読み上げ用の入力方式に切り替わり、普通のタップを
 * 受け付けなくなる。そのためキーボードが表示されている間はタッチ探索モードを切る。
 *
 * スレッド: ウィンドウ・判定範囲の管理はメインスレッド、タッチ判定は専用スレッド (touchHandler)。
 * 両者の受け渡しは不変な zone のスナップショットと controller の参照だけ。
 */
class SwipeDrawerService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val TAG = "ClaudeSwipe"
        const val CLAUDE_PKG = "com.anthropic.claude"
        const val PREFS = "settings"
        const val PREF_SHOW_ZONE = "show_zone"
        const val PREF_SHOW_ZONE_DEFAULT = false

        private val MENU_DESCS = setOf("メニューを開く", "Open menu")
        private val DRAWER_OPEN_DESCS = setOf("ナビゲーションメニューを閉じる", "Close navigation menu")
        /** トップ画面 (新規チャット) にだけある右上のボタン */
        private val TOP_SCREEN_DESCS = setOf("シークレットチャット", "Incognito chat")

        /** 動かないまま経過したらアプリへ返すまでの時間 (長押しを通すため) */
        private const val DELEGATE_TIMEOUT_MS = 120L
        /** この距離だけ右へ動いたらサイドバーを開く */
        private const val OPEN_DISTANCE_DP = 48f
        /** 作り直したタップの押下時間 (返すまでの往復より長く取る) */
        private const val INJECTED_TAP_MS = 80L
        private const val INJECTED_MATCH_WINDOW_MS = 500L
        private const val INJECTED_MATCH_PX = 12f
        /** 画面が変化した時の範囲更新 (quick) の最短間隔 */
        private const val ZONE_UPDATE_THROTTLE_MS = 100L
        /** quick だけで済ませる最長時間。これを過ぎたら画面構造を全部たどり直す */
        private const val FULL_RESCAN_MS = 2000L
        /** サイドバーの開閉アニメーションが落ち着くまでの時間 */
        private const val DRAWER_SETTLE_MS = 350L
        private val PASSTHROUGH_RETRY_MS = longArrayOf(0L, 100L, 300L)
        /** メニューボタンを押したとみなす、ボタン周囲の余白 */
        private const val MENU_HIT_SLOP_DP = 16f
        private val FULL_UPDATE_TOKEN = Any()
        private const val PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST

        @Volatile
        var instance: SwipeDrawerService? = null
            private set
    }

    private enum class Phase { IDLE, UNDECIDED, CLAIMED, RELEASED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchThread = HandlerThread("ClaudeSwipe-touch").apply { start() }
    private val touchHandler = Handler(touchThread.looper)
    private val touchExecutor = Executor { touchHandler.post(it) }
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var overlay: ZoneOverlay? = null

    // ---- メインスレッドが書き、タッチスレッドが読む ----
    @Volatile private var controller: TouchInteractionController? = null
    /** スワイプを判定する範囲 (画面座標)。差し替えるだけで中身は書き換えない */
    @Volatile private var zone = Rect()
    /** メニューボタンの位置 (画面座標、押したとみなす余白込み)。差し替えるだけで中身は書き換えない */
    @Volatile private var menuRect = Rect()
    /** サイドバーが開いている間の幕の位置 (画面座標)。閉じている間は空 */
    @Volatile private var scrimRect = Rect()

    // ---- メインスレッド専用 ----
    /** Claude が前面にあり、判定範囲を追いかけている */
    private var tracking = false
    /** タッチ探索モードを使っている (Claude が前面かつキーボード非表示) */
    private var active = false
    private var keyboardShown = false
    /** キーボード表示中にトップ画面で使う透明な受け口 */
    private var catcher: SwipeCatcher? = null
    private val claudeBounds = Rect()
    private var menuNode: AccessibilityNodeInfo? = null
    private var editNode: AccessibilityNodeInfo? = null
    /** サイドバーが開いている間だけ存在する幕 (「ナビゲーションメニューを閉じる」) */
    private var scrimNode: AccessibilityNodeInfo? = null
    /** トップ画面にいる間だけ存在する「シークレットチャット」ボタン */
    private var topScreenNode: AccessibilityNodeInfo? = null
    private var lastFullScan = 0L
    /** 直前に有効だった範囲 (サイドバーを閉じた瞬間に先回りで戻すため) */
    private var lastGoodZone = Rect()
    /** 先回りで範囲を変えた後、答え合わせまで途中の更新で上書きしない期限 */
    private var holdUntil = 0L
    private var quickUpdateScheduled = false
    private var lastZoneUpdate = 0L

    // ---- タッチスレッド専用 ----
    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var opened = false
    private var pendingTap: PointF? = null
    private var pendingTapTime = 0L

    private var touchSlop = 0
    private var openDistancePx = 0f

    private val delegateOnTimeout = Runnable {
        if (phase == Phase.UNDECIDED) delegate("timeout")
    }
    private val quickUpdater = Runnable {
        quickUpdateScheduled = false
        updateZone(full = false)
    }

    private val touchCallback = object : TouchInteractionController.Callback {
        override fun onMotionEvent(event: MotionEvent) = handleMotion(event)

        override fun onStateChanged(state: Int) {
            if (state == TouchInteractionController.STATE_CLEAR) {
                touchHandler.removeCallbacks(delegateOnTimeout)
                phase = Phase.IDLE
            }
        }
    }

    // ---- ライフサイクル ------------------------------------------------------------------

    override fun onServiceConnected() {
        instance = this
        val density = resources.displayMetrics.density
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        openDistancePx = OPEN_DISTANCE_DP * density
        prefs.registerOnSharedPreferenceChangeListener(this)
        refreshActive()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        touchThread.quitSafely()
        super.onDestroy()
    }

    private fun shutdown() {
        setActive(false)
        setTracking(false)
        catcher?.hide()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        instance = null
    }

    override fun onInterrupt() = Unit

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        when (key) {
            PREF_SHOW_ZONE -> renderOverlay()
            in ZoneSettings.KEYS -> requestZoneUpdate(full = true, delay = 0)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                refreshActive()
                requestZoneUpdate(full = true, delay = 0)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (tracking && event.packageName == CLAUDE_PKG) requestZoneUpdate(full = false, delay = -1)
            }
        }
    }

    // ---- 有効にする条件 --------------------------------------------------------------------

    private fun refreshActive() {
        val ws = windows
        // windows には画面に見えているウィンドウだけが入る。画面分割で設定画面と並べた時も
        // 追いかけられるよう、最前面かどうかではなく Claude が見えているかで判断する
        val claudeWin = ws.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == CLAUDE_PKG
        }
        val claudeInFront = claudeWin != null
        claudeWin?.getBoundsInScreen(claudeBounds)
        val kb = ws.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val kbChanged = kb != keyboardShown
        keyboardShown = kb
        setTracking(claudeInFront)
        // キーボードはタッチ探索モードが有効だと普通のタップを受け付けなくなるので、表示中は切る
        setActive(claudeInFront && !kb)
        if (kbChanged) renderOverlay()
    }

    private fun setTracking(value: Boolean) {
        if (value == tracking) return
        tracking = value
        Log.i(TAG, "tracking=$value")
        if (value) {
            updateZone(full = true)
        } else {
            forgetNodes()
            mainHandler.removeCallbacks(quickUpdater)
            mainHandler.removeCallbacksAndMessages(FULL_UPDATE_TOKEN)
            quickUpdateScheduled = false
            setZone(Rect())
        }
    }

    private fun setActive(value: Boolean) {
        if (value == active) return
        active = value
        Log.i(TAG, "active=$value")
        val info = serviceInfo ?: return
        if (value) {
            // コールバックを先に登録しておくと、タッチ探索の既定動作 (TalkBack 相当) に入らない
            controller = getTouchInteractionController(Display.DEFAULT_DISPLAY).also {
                it.registerCallback(touchExecutor, touchCallback)
            }
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            serviceInfo = info
            // パススルー領域はタッチ探索が有効になってから作られる内部オブジェクトに入る。
            // 作られる時刻が読めないので数回設定し直す (範囲の計算はせず、設定だけなので軽い)
            for (d in PASSTHROUGH_RETRY_MS) mainHandler.postDelayed({ if (active) applyPassthrough(zone) }, d)
        } else {
            info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            serviceInfo = info
            controller?.unregisterAllCallbacks()
            controller = null
            touchHandler.post {
                touchHandler.removeCallbacks(delegateOnTimeout)
                phase = Phase.IDLE
            }
        }
    }

    // ---- 判定範囲 --------------------------------------------------------------------------
    //
    // 画面構造を全部たどる (full) のは 1 回 15〜50ms の CPU を使う。普段は覚えておいたノード
    // (メニューボタン・入力欄・サイドバーの幕) だけを refresh() で問い合わせ直す (quick)。
    // quick で辻褄が合わなくなった時と、最後の full から FULL_RESCAN_MS 経った時だけ full にする。

    /** @param delay full の場合の遅延 (ms)。quick は間引き付きで即時に実行する */
    private fun requestZoneUpdate(full: Boolean, delay: Long) {
        if (!tracking) return
        if (full) {
            mainHandler.postAtTime({ updateZone(full = true) }, FULL_UPDATE_TOKEN,
                SystemClock.uptimeMillis() + delay)
            return
        }
        if (quickUpdateScheduled) return
        quickUpdateScheduled = true
        val wait = (ZONE_UPDATE_THROTTLE_MS - (SystemClock.uptimeMillis() - lastZoneUpdate)).coerceAtLeast(0)
        mainHandler.postDelayed(quickUpdater, wait)
    }

    private fun updateZone(full: Boolean) {
        if (!tracking) return
        if (SystemClock.uptimeMillis() < holdUntil) return
        lastZoneUpdate = SystemClock.uptimeMillis()
        val stale = SystemClock.uptimeMillis() - lastFullScan > FULL_RESCAN_MS
        var z = if (full || stale) null else quickZone()
        if (z == null) {
            z = fullScan()
            lastFullScan = SystemClock.uptimeMillis()
        }
        setZone(z)
    }

    /**
     * サイドバーの開閉は押した位置で結果がほぼ決まる。アニメーションの終わりを待たずに範囲を
     * [z] にしておき、落ち着いた頃に画面構造を見直して答え合わせをする。
     */
    private fun predictZone(z: Rect) {
        holdUntil = SystemClock.uptimeMillis() + DRAWER_SETTLE_MS
        setZone(z)
        mainHandler.removeCallbacksAndMessages(FULL_UPDATE_TOKEN)
        requestZoneUpdate(full = true, delay = DRAWER_SETTLE_MS)
    }

    private fun setZone(z: Rect) {
        val changed = z != zone
        zone = z
        if (!z.isEmpty) lastGoodZone = z
        if (changed) renderOverlay()
        if (changed || !z.isEmpty) applyPassthrough(z)
    }

    /** 覚えておいたノードだけで範囲を出す。構造が変わっていたら null (full が必要) */
    private fun quickZone(): Rect? {
        scrimNode?.let {
            // サイドバーが開いている間はアプリの「スワイプで閉じる」に任せる
            if (it.refresh() && it.isVisibleToUser) return Rect()
            scrimNode = null
            return null // 閉じた。メニューボタン等の位置も含めて見直す
        }
        val menu = menuNode ?: return null
        if (!menu.refresh()) return null
        topScreenNode?.let { if (!it.refresh()) return null }
        val edit = editNode
        if (edit != null && !edit.refresh()) return null
        return zoneFrom(menu, edit)
    }

    private fun fullScan(): Rect {
        forgetNodes()
        val root = claudeWindow()?.getRoot(PREFETCH) ?: return Rect()
        var menu: AccessibilityNodeInfo? = null
        walk(root) { node ->
            val desc = node.contentDescription?.toString()
            if (desc != null) {
                if (desc in MENU_DESCS) menu = node
                if (desc in DRAWER_OPEN_DESCS) scrimNode = node
                if (desc in TOP_SCREEN_DESCS) topScreenNode = node
            }
            if (editNode == null && node.className == "android.widget.EditText") editNode = node
        }
        menuNode = menu?.let { clickableAncestor(it) }
        scrimRect = scrimNode?.let { n -> Rect().also { n.getBoundsInScreen(it) } } ?: Rect()
        if (scrimNode != null) return Rect()
        val m = menuNode ?: return Rect()
        return zoneFrom(m, editNode)
    }

    private fun forgetNodes() {
        menuNode = null
        editNode = null
        scrimNode = null
        topScreenNode = null
    }

    private fun zoneFrom(menu: AccessibilityNodeInfo, edit: AccessibilityNodeInfo?): Rect {
        val win = claudeBounds
        val density = resources.displayMetrics.density
        val menuRect = Rect().also { menu.getBoundsInScreen(it) }
        val slop = (MENU_HIT_SLOP_DP * density).toInt()
        this.menuRect = Rect(menuRect).apply { inset(-slop, -slop) }
        var bottom = win.bottom
        edit?.let {
            val r = Rect().also { rect -> it.getBoundsInScreen(rect) }
            bottom = minOf(bottom, r.top - (setting(ZoneSettings.KEY_BOTTOM_DP) * density).toInt())
        }
        val top = menuRect.bottom + (setting(ZoneSettings.KEY_TOP_DP) * density).toInt()
        val left = win.left + (setting(ZoneSettings.KEY_EDGE_DP) * density).toInt()
        val right = win.left + win.width() * setting(ZoneSettings.KEY_WIDTH_PCT) / 100
        if (bottom - top < 48 * density || right <= left) return Rect()
        return Rect(left, top, right, bottom)
    }

    private fun setting(key: String) = ZoneSettings.get(prefs, key)

    private fun applyPassthrough(z: Rect) {
        val c = controller ?: return
        @Suppress("DEPRECATION")
        val display = (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        val size = android.graphics.Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
        val region = Region(0, 0, size.x, size.y)
        if (!z.isEmpty) region.op(z, Region.Op.DIFFERENCE)
        setTouchExplorationPassthroughRegion(c.displayId, region)
    }

    private fun renderOverlay() {
        val z = zone
        val useCatcher = tracking && keyboardShown && topScreenNode != null && !z.isEmpty
        if (useCatcher) {
            val c = catcher ?: SwipeCatcher(this, openDistancePx, ::openDrawer, ::forwardTap)
                .also { catcher = it }
            c.show(z)
        } else {
            catcher?.hide()
        }
        // 枠の表示は「実際にスワイプを受け付けている範囲」だけにする
        val working = active || useCatcher
        val show = tracking && working && !z.isEmpty && prefs.getBoolean(PREF_SHOW_ZONE, PREF_SHOW_ZONE_DEFAULT)
        if (!show) {
            overlay?.hide()
            return
        }
        val o = overlay ?: ZoneOverlay(this).also { overlay = it }
        o.show(z)
    }

    /** 受け口の上でのタップを、受け口を一瞬すり抜け可能にしてから Claude へ渡し直す */
    private fun forwardTap(x: Float, y: Float) {
        val c = catcher ?: return
        c.setPassThrough(true)
        val restore = Runnable { c.setPassThrough(false) }
        // すり抜けの設定が入力系に反映されるのを待ってから押す
        mainHandler.postDelayed({
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { mainHandler.postDelayed(restore, 50) }
                override fun onCancelled(g: GestureDescription?) { mainHandler.post(restore) }
            }, mainHandler)
            if (!ok) Log.w(TAG, "catcher forward tap failed")
        }, 50)
        // 念のため必ず戻す
        mainHandler.postDelayed(restore, 600)
    }

    // ---- タッチ判定 (タッチスレッド) --------------------------------------------------------

    private fun handleMotion(event: MotionEvent) {
        val c = controller ?: return
        // パススルー領域で始まったタッチも通知だけは届く。既に素通し中なら何もしない
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            c.state != TouchInteractionController.STATE_TOUCH_INTERACTING
        ) {
            phase = Phase.RELEASED
            // Claude はメニューボタンを押してもクリックの通知を出さないので、押した位置で判断する
            val px = event.rawX.toInt()
            val py = event.rawY.toInt()
            when {
                scrimRect.contains(px, py) -> mainHandler.post { predictZone(lastGoodZone) }
                menuRect.contains(px, py) -> mainHandler.post { predictZone(Rect()) }
            }
            return
        }
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                opened = false
                phase = Phase.UNDECIDED
                val tap = pendingTap
                if (tap != null && SystemClock.uptimeMillis() - pendingTapTime < INJECTED_MATCH_WINDOW_MS &&
                    hypot(tap.x - x, tap.y - y) < INJECTED_MATCH_PX
                ) {
                    pendingTap = null
                    delegate("injected-tap")
                    return
                }
                if (!zone.contains(x.toInt(), y.toInt())) {
                    delegate("outside-zone")
                    return
                }
                touchHandler.postDelayed(delegateOnTimeout, DELEGATE_TIMEOUT_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (phase == Phase.UNDECIDED) delegate("multi-touch")
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                when (phase) {
                    Phase.UNDECIDED -> if (hypot(dx, dy) > touchSlop) {
                        if (dx > 0 && dx > abs(dy) * 1.5f) {
                            touchHandler.removeCallbacks(delegateOnTimeout)
                            phase = Phase.CLAIMED
                        } else {
                            delegate("move dx=${dx.toInt()} dy=${dy.toInt()}")
                        }
                    }
                    Phase.CLAIMED -> if (!opened && dx > openDistancePx) {
                        opened = true
                        openDrawer()
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                touchHandler.removeCallbacks(delegateOnTimeout)
                if (phase == Phase.UNDECIDED) {
                    injectTap(x, y)
                } else if (phase == Phase.CLAIMED && !opened && x - downX > openDistancePx) {
                    opened = true
                    openDrawer()
                }
                phase = Phase.IDLE
            }
            MotionEvent.ACTION_CANCEL -> {
                touchHandler.removeCallbacks(delegateOnTimeout)
                phase = Phase.IDLE
            }
        }
    }

    private fun delegate(reason: String) {
        touchHandler.removeCallbacks(delegateOnTimeout)
        phase = Phase.RELEASED
        val c = controller ?: return
        if (c.state == TouchInteractionController.STATE_TOUCH_INTERACTING) {
            c.requestDelegating()
        } else {
            Log.w(TAG, "delegate skipped ($reason) state=${TouchInteractionController.stateToString(c.state)}")
        }
    }

    /** 指を離すまで判定がつかなかったタップを作り直してアプリへ渡す */
    private fun injectTap(x: Float, y: Float) {
        pendingTap = PointF(x, y)
        pendingTapTime = SystemClock.uptimeMillis()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, INJECTED_TAP_MS))
            .build()
        if (!dispatchGesture(gesture, null, null)) Log.w(TAG, "inject tap failed")
    }

    /** タッチスレッドから呼ぶ。ノードはメインスレッドが持っているのでメインで押す */
    private fun openDrawer() {
        mainHandler.post {
            val button = menuNode?.takeIf { it.refresh() }
                ?: run { fullScan(); menuNode }
            val ok = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            Log.i(TAG, "open drawer ok=$ok")
            if (ok) {
                // 開いたのは分かっているので、幕のノードを待たずに範囲を消す
                predictZone(Rect())
            }
        }
    }

    // ---- ノード操作 ------------------------------------------------------------------------

    private fun claudeWindow(): AccessibilityWindowInfo? = windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == CLAUDE_PKG
    }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            node.getChild(i, PREFETCH)?.let { walk(it, visit) }
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n
            n = n.parent
        }
        return null
    }
}
