package io.github.mesteriis.lik

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mesteriis.lik.similarity.ComparisonActivity
import io.github.mesteriis.lik.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimilarityUiTest {
    @Test fun moreExposesManualSimilarityIndexAndComparisonEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.nav_more).performClick()
            val entry=activity.findViewById<android.view.View>(R.id.organization_similarity);assertNotNull(entry);entry.performClick()
            assertNotNull(activity.findViewById<android.view.View>(R.id.organization_similarity_run))
        }}
    }

    @Test fun comparisonActivityRejectsMissingOrStalePairWithoutShowingPhotos() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent=Intent(context,ComparisonActivity::class.java).putExtra(ComparisonActivity.EXTRA_LEFT,"missing").putExtra(ComparisonActivity.EXTRA_RIGHT,"other")
        ActivityScenario.launch<ComparisonActivity>(intent).use { scenario -> scenario.onActivity { activity ->
            assertEquals(android.view.View.VISIBLE,activity.findViewById<android.view.View>(R.id.comparison_unavailable).visibility)
            assertEquals(android.view.View.GONE,activity.findViewById<android.view.View>(R.id.comparison_content).visibility)
        }}
    }
}
