package io.github.mesteriis.lik

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconSelectionTest {
    private val choices = listOf(
        R.id.icon_classic to "Classic",
        R.id.icon_emerald to "Emerald",
        R.id.icon_amethyst to "Amethyst",
        R.id.icon_sapphire to "Sapphire",
        R.id.icon_sunrise to "Sunrise",
        R.id.icon_moonstone to "Moonstone",
        R.id.icon_rose to "Rose",
        R.id.icon_ice to "Ice",
        R.id.icon_aurora to "Aurora",
        R.id.icon_amber to "Amber",
    )

    @Test
    fun everyIconCanBeSelectedWithoutLosingTheLauncher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val settings = Intent().setClassName(context, "${context.packageName}.settings.SettingsActivity")
        assertNotNull("The icon settings screen must be registered", pm.resolveActivity(settings, 0))
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)

        try {
            ActivityScenario.launch<Activity>(settings).use { scenario ->
                // Also return to the default and reselect an alternative in the same session.
                for ((viewId, alias) in choices + choices.first() + choices.last()) {
                    scenario.onActivity { activity ->
                        val option = activity.findViewById<RadioButton>(viewId)
                        assertNotNull("The picker must include $alias", option)
                        option.performClick()
                        assertTrue("The selected icon must be marked", option.isChecked)
                        val launchers = pm.queryIntentActivities(launcher, 0)
                        assertEquals("Exactly one launcher must remain enabled", 1, launchers.size)
                        assertEquals("${context.packageName}.launcher.$alias", launchers.single().activityInfo.name)
                        assertTrue("Every alias must have its own artwork", launchers.single().activityInfo.icon != 0)
                    }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<RadioButton>(R.id.icon_amber).isChecked)
                }
            }

            val launchIntent = requireNotNull(pm.getLaunchIntentForPackage(context.packageName))
            assertEquals("${context.packageName}.launcher.Amber", launchIntent.component?.className)
            ActivityScenario.launch<Activity>(launchIntent).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<android.view.View>(R.id.app_title).isShown)
                }
            }
        } finally {
            pm.setComponentEnabledSettings(choices.map { (_, alias) ->
                PackageManager.ComponentEnabledSetting(
                    ComponentName(context.packageName, "${context.packageName}.launcher.$alias"),
                    if (alias == "Classic") PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            })
        }
    }
}
