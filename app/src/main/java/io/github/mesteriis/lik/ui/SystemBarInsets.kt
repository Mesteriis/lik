package io.github.mesteriis.lik.ui

import android.view.View
import android.view.WindowInsets

fun View.applySystemBarInsets() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        val safeInsets = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        view.setPadding(
            left + safeInsets.left,
            top + safeInsets.top,
            right + safeInsets.right,
            bottom + safeInsets.bottom,
        )
        insets
    }
    requestApplyInsets()
}
