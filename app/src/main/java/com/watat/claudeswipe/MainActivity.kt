package com.watat.claudeswipe

import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Intent
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * メイン画面。よく使うもの (状態・判定範囲) だけを置き、
 * 言語は 1 行 + ダイアログ、それ以外は「このアプリについて」へ分ける。
 */
class MainActivity : Activity() {

    private lateinit var statusCard: LinearLayout
    private val sliders = mutableListOf<Pair<ZoneSettings.Item, SeekBar>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(SwipeDrawerService.PREFS, MODE_PRIVATE)
        val root = Ui.page(this, getString(R.string.app_name))

        // 状態 (中身は onResume で作る。設定画面から戻った時に更新するため)
        statusCard = Ui.card(this)
        root.addView(statusCard, Ui.block(this, bottom = 8))

        // 判定範囲
        root.addView(Ui.heading(this, getString(R.string.zone_heading)))
        val zoneCard = Ui.card(this)
        root.addView(zoneCard, Ui.block(this, bottom = 8))
        zoneCard.addView(Switch(this).apply {
            textSize = 16f
            setText(R.string.show_zone)
            isChecked = prefs.getBoolean(SwipeDrawerService.PREF_SHOW_ZONE, SwipeDrawerService.PREF_SHOW_ZONE_DEFAULT)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(SwipeDrawerService.PREF_SHOW_ZONE, checked).apply()
            }
        }, Ui.block(this, bottom = 8))
        for (item in ZoneSettings.ITEMS) addSlider(zoneCard, item, prefs)
        zoneCard.addView(Button(this).apply {
            setText(R.string.reset_defaults)
            setOnClickListener {
                val e = prefs.edit()
                for ((item, bar) in sliders) {
                    e.putInt(item.key, item.default)
                    bar.progress = item.default - item.min
                }
                e.apply()
            }
        }, Ui.block(this, bottom = 4))
        zoneCard.addView(Ui.note(this, getString(R.string.zone_tip)))

        // その他
        root.addView(Ui.divider(this))
        Ui.row(root, getString(R.string.language_heading), currentLanguageLabel()) { showLanguageDialog() }
        Ui.row(root, getString(R.string.about_heading)) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        Ui.row(root, getString(R.string.support_row), chevron = false) {
            Ui.openUrl(this, Links.BUY_ME_A_COFFEE)
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        statusCard.removeAllViews()
        val enabled = SwipeDrawerService.instance != null
        statusCard.addView(TextView(this).apply {
            textSize = 18f
            text = getString(if (enabled) R.string.status_enabled_title else R.string.status_disabled_title)
            setTextColor(getColor(if (enabled) R.color.accent else R.color.warning))
        }, Ui.block(this, bottom = 6))
        if (enabled) {
            statusCard.addView(Ui.body(this, getString(R.string.status_enabled_body)))
        } else {
            // 有効になるまでは手順を見せる。有効になったら邪魔なので隠す
            statusCard.addView(
                Ui.body(this, getString(R.string.setup_steps, getString(R.string.service_label))),
                Ui.block(this, bottom = 10)
            )
            statusCard.addView(Button(this).apply {
                setText(R.string.open_accessibility)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
        }
    }

    /** 1 項目 = 「名前 ………… 値」の行 + スライダー + 説明 */
    private fun addSlider(card: LinearLayout, item: ZoneSettings.Item, prefs: android.content.SharedPreferences) {
        val valueView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.END
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
            addView(TextView(this@MainActivity).apply {
                textSize = 15f
                setText(item.label)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
        })
        val bar = SeekBar(this).apply {
            max = item.max - item.min
            progress = ZoneSettings.get(prefs, item.key) - item.min
        }
        fun show(v: Int) {
            val number = "$v ${item.unit}"
            valueView.text = if (v == item.default) getString(R.string.zone_value_default, number) else number
        }
        show(bar.progress + item.min)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + item.min
                show(v)
                if (fromUser) prefs.edit().putInt(item.key, v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        card.addView(bar)
        card.addView(Ui.note(this, getString(item.help)), Ui.block(this, bottom = 4))
        sliders += item to bar
    }

    // ---- 言語 ------------------------------------------------------------------------------

    /** 表示名はその言語自身で書く (今の表示言語が読めなくても選べるように) */
    private fun languageChoices() = listOf(
        "" to getString(R.string.language_system),
        "en" to "English",
        "ja" to "日本語",
    )

    private fun currentLanguageLabel(): String {
        val current = getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        return languageChoices().firstOrNull { it.first == current }?.second ?: current
    }

    /**
     * 端末の「設定 → アプリ → 言語」と同じ仕組み (LocaleManager) なので、どちらで変えても揃う。
     * 変えると画面が作り直されて新しい言語で表示される。
     */
    private fun showLanguageDialog() {
        val lm = getSystemService(LocaleManager::class.java)
        val choices = languageChoices()
        val current = choices.indexOfFirst { it.first == lm.applicationLocales.toLanguageTags() }
        AlertDialog.Builder(this)
            .setTitle(R.string.language_heading)
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) lm.applicationLocales = LocaleList.forLanguageTags(choices[which].first)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
