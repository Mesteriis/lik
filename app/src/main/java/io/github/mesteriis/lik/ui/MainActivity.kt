package io.github.mesteriis.lik.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.settings.AppIconManager
import io.github.mesteriis.lik.settings.SettingsActivity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main_content).applySystemBarInsets()
        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<ImageView>(R.id.app_emblem)
            .setImageResource(AppIconManager(this).selected().iconRes)
    }
}
