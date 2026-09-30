package com.watat.claudeswipe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 画面部品をコードで組むための小さな道具 (XML レイアウトは使っていない) */
object Ui {
    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun themeColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }

    /** 端末テーマの既定の文字色は薄いことがあるので、見出しや項目名には主文字色を明示する */
    fun primaryText(context: Context) = themeColor(context, android.R.attr.textColorPrimary)

    /** 文字色を薄く敷いた面。明暗どちらのテーマでも背景から少し浮いて見える */
    private fun surface(context: Context, alpha: Int = 18): GradientDrawable {
        val base = themeColor(context, android.R.attr.textColorPrimary)
        return GradientDrawable().apply {
            cornerRadius = dp(context, 16).toFloat()
            setColor((base and 0x00FFFFFF) or (alpha shl 24))
        }
    }

    /**
     * 画面の土台。スクロールでき、システムバー (targetSdk 35 以降は全画面描画が強制) の分だけ内側に寄せる。
     * 中身は返した LinearLayout に足す。
     */
    fun page(activity: Activity, title: String): LinearLayout {
        val pad = dp(activity, 20)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad * 2 + bars.bottom)
                insets
            }
        }
        root.addView(TextView(activity).apply {
            textSize = 24f
            text = title
            setTextColor(primaryText(activity))
        }, block(activity, bottom = 20))
        activity.setContentView(ScrollView(activity).apply { addView(root) })
        return root
    }

    fun block(context: Context, bottom: Int = 12) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(context, bottom) }

    /** 角丸の面に中身を入れたカード */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = surface(context)
        val p = dp(context, 16)
        setPadding(p, p, p, p)
    }

    fun heading(context: Context, text: String) = TextView(context).apply {
        textSize = 14f
        this.text = text
        setTextColor(context.getColor(R.color.accent))
        setPadding(dp(context, 4), dp(context, 12), 0, dp(context, 4))
    }

    fun body(context: Context, text: CharSequence, size: Float = 14f) = TextView(context).apply {
        textSize = size
        this.text = text
    }

    fun note(context: Context, text: CharSequence) = body(context, text, 12f).apply { alpha = 0.7f }

    fun divider(context: Context) = View(context).apply {
        background = surface(context, alpha = 40)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1))
            .apply { topMargin = dp(context, 8); bottomMargin = dp(context, 8) }
    }

    /**
     * 押せる 1 行 (設定アプリの項目と同じ形)。左に名前、右に今の値と「›」。
     * 値を後から変える時のために、値の TextView を返す。
     */
    fun row(
        container: LinearLayout,
        title: String,
        value: String? = null,
        chevron: Boolean = true,
        onClick: () -> Unit,
    ): TextView {
        val context = container.context
        val valueView = TextView(context).apply {
            textSize = 14f
            alpha = 0.7f
            text = value ?: ""
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 56)
            setPadding(dp(context, 4), 0, dp(context, 4), 0)
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                textSize = 16f
                text = title
                setTextColor(primaryText(context))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
            if (chevron) addView(TextView(context).apply {
                textSize = 20f
                alpha = 0.5f
                text = "›"
                setPadding(dp(context, 12), 0, 0, 0)
            })
        }
        container.addView(row)
        return valueView
    }

    /** インターネット権限は持たないので、URL はブラウザに開かせるだけにする */
    fun openUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
