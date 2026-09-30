package com.watat.claudeswipe

import android.app.Activity
import android.os.Bundle

/** 「このアプリについて」: プライバシー・非公式の表記・バージョン・ライセンス・ソース・応援 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.page(this, getString(R.string.about_heading))

        root.addView(Ui.heading(this, getString(R.string.privacy_heading)))
        root.addView(Ui.card(this).apply {
            addView(Ui.body(this@AboutActivity, getString(R.string.privacy_body)))
        }, Ui.block(this, bottom = 8))

        root.addView(Ui.heading(this, getString(R.string.info_heading)))
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        Ui.row(root, getString(R.string.version_label), version, chevron = false) {}
        Ui.row(root, getString(R.string.license_label), "MIT") { Ui.openUrl(this, Links.LICENSE) }
        Ui.row(root, getString(R.string.source_label), "GitHub") { Ui.openUrl(this, Links.SOURCE) }
        Ui.row(root, getString(R.string.support_row), chevron = false) { Ui.openUrl(this, Links.BUY_ME_A_COFFEE) }

        root.addView(Ui.divider(this))
        root.addView(Ui.note(this, getString(R.string.disclaimer)))
    }
}
