package com.watat.claudeswipe

import android.content.SharedPreferences

/** 判定範囲の調整値。アプリ画面で変更し、サービスが読む */
object ZoneSettings {
    const val KEY_WIDTH_PCT = "zone_width_pct"
    const val KEY_EDGE_DP = "zone_edge_dp"
    const val KEY_TOP_DP = "zone_top_dp"
    const val KEY_BOTTOM_DP = "zone_bottom_dp"

    val KEYS = setOf(KEY_WIDTH_PCT, KEY_EDGE_DP, KEY_TOP_DP, KEY_BOTTOM_DP)

    /** 調整項目の定義 (画面の並び順)。label / help は文字列リソース ID */
    data class Item(
        val key: String,
        val label: Int,
        val unit: String,
        val min: Int,
        val max: Int,
        val default: Int,
        val help: Int,
    )

    val ITEMS = listOf(
        Item(KEY_WIDTH_PCT, R.string.zone_width, "%", 20, 100, 60, R.string.zone_width_help),
        Item(KEY_EDGE_DP, R.string.zone_edge, "dp", 0, 80, 32, R.string.zone_edge_help),
        Item(KEY_TOP_DP, R.string.zone_top, "dp", 0, 200, 8, R.string.zone_top_help),
        Item(KEY_BOTTOM_DP, R.string.zone_bottom, "dp", 0, 200, 24, R.string.zone_bottom_help),
    )

    fun get(prefs: SharedPreferences, key: String): Int {
        val item = ITEMS.first { it.key == key }
        return prefs.getInt(key, item.default).coerceIn(item.min, item.max)
    }
}
