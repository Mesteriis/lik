package io.github.mesteriis.lik.settings

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.ui.applySystemBarInsets
import kotlin.math.roundToInt

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.settings_content).applySystemBarInsets()
        findViewById<View>(R.id.close_settings).setOnClickListener { finish() }

        val iconManager = AppIconManager(this)
        val options = findViewById<RadioGroup>(R.id.icon_options)
        val iconSize = (56 * resources.displayMetrics.density).roundToInt()
        for (icon in AppIcon.entries) {
            val option = layoutInflater.inflate(R.layout.item_icon_option, options, false) as RadioButton
            option.id = icon.optionId
            option.setText(icon.labelRes)
            val preview = requireNotNull(getDrawable(icon.iconRes)).apply {
                setBounds(0, 0, iconSize, iconSize)
            }
            option.setCompoundDrawablesRelative(preview, null, null, null)
            options.addView(option)
        }
        options.check(iconManager.selected().optionId)

        var restoringSelection = false
        options.setOnCheckedChangeListener { group, checkedId ->
            if (restoringSelection || checkedId == View.NO_ID) return@setOnCheckedChangeListener
            val icon = AppIcon.entries.first { it.optionId == checkedId }
            try {
                iconManager.select(icon)
            } catch (_: RuntimeException) {
                // A failed package-manager transaction leaves the previous selection active.
                restoringSelection = true
                group.check(iconManager.selected().optionId)
                restoringSelection = false
                Toast.makeText(this, R.string.icon_change_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
