package io.github.mesteriis.lik.settings

import android.content.Context
import android.content.pm.PackageManager

/** Android persists component states, so there is no second preference that can drift. */
class AppIconManager(context: Context) {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    fun selected(): AppIcon = AppIcon.entries.first { icon ->
        when (packageManager.getComponentEnabledSetting(icon.componentName(packageName))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.CLASSIC
            else -> false
        }
    }

    fun select(icon: AppIcon) {
        // One atomic update keeps a single launcher enabled, including when returning to Classic.
        packageManager.setComponentEnabledSettings(AppIcon.entries.map { candidate ->
            PackageManager.ComponentEnabledSetting(
                candidate.componentName(packageName),
                if (candidate == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        })
    }
}
