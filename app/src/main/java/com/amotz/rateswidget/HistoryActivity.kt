package com.amotz.rateswidget

import android.app.Activity
import android.os.Bundle

/** Transparent host for the history popup, so a widget row opens only the chart (not the settings screen). */
class HistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = intent.getStringExtra(RatesWidget.EXTRA_CODE)?.let { Catalog.get(it) }
        if (item == null) { finish(); return }
        HistoryDialog(this, item) { finish() }.show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
