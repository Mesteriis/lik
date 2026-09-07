package io.github.mesteriis.lik

import android.app.Activity
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @Test
    fun launcherOpensStartScreenAfterActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("The application must expose a launcher activity", intent)

        ActivityScenario.launch<Activity>(requireNotNull(intent)).use { scenario ->
            scenario.onActivity(::assertStartScreen)
            scenario.recreate()
            scenario.onActivity(::assertStartScreen)
        }
    }

    private fun assertStartScreen(activity: Activity) {
        val title = activity.findViewById<TextView>(R.id.app_title)
        assertNotNull("The start screen must be attached", title)
        assertEquals(activity.getString(R.string.app_name), title.text.toString())
        assertTrue("The app title must be visible", title.isShown)
    }
}
