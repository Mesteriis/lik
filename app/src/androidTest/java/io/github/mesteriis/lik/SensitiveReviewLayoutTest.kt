package io.github.mesteriis.lik

import android.content.res.Configuration
import android.graphics.Rect
import android.os.LocaleList
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SensitiveReviewLayoutTest {
    @Test fun bothDecisionsAreReachableAtNarrowWidthsWithRussianAndLargeFonts() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for(locale in listOf("en","ru"))for(font in listOf(1f,2f))for(widthDp in listOf(280,320)) {
                val config=Configuration(instrumentation.targetContext.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(locale)));fontScale=font
                }
                val context=android.view.ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config),io.github.mesteriis.lik.R.style.Theme_Lik)
                val density=context.resources.displayMetrics.density
                val width=(widthDp*density).toInt();val height=(1000*density).toInt()
                val parent=FrameLayout(context)
                val root=LayoutInflater.from(context).inflate(R.layout.activity_photo_viewer,parent,false)
                parent.addView(root)
                val buttons=listOf(R.id.viewer_mark_safe,R.id.viewer_mark_sensitive).map { root.findViewById<Button>(it).apply { visibility=View.VISIBLE } }
                parent.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                parent.layout(0,0,width,height)
                buttons.forEach { button ->
                    button.requestRectangleOnScreen(Rect(0,0,button.width,button.height),true)
                    val visible=Rect()
                    assertTrue("$locale font=$font width=$widthDp: ${button.text} is clipped",button.getGlobalVisibleRect(visible))
                    assertEquals("Full action must be reachable by scrolling",button.width,visible.width())
                    assertTrue(visible.height()>=48*density)
                }
            }
        }
    }
}
