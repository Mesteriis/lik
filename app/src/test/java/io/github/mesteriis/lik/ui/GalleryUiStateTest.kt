package io.github.mesteriis.lik.ui

import io.github.mesteriis.lik.gallery.TimelineLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryUiStateTest {
    @Test fun galleryStartsOnDaysAndZoomChangesOneLevelAtATime() {
        val initial = GalleryUiState()

        assertEquals(TimelineLevel.DAYS, initial.level)
        assertEquals(TimelineLevel.PHOTO, initial.zoomIn().level)
        assertEquals(TimelineLevel.WEEKS, initial.zoomOut().level)
    }

    @Test fun sectionAndAnchorArePreservedWhenChangingScale() {
        val state = GalleryUiState(
            level = TimelineLevel.MONTHS,
            section = GallerySection.PLACES,
            anchorId = "photo-42",
            anchorOffset = 18,
        )

        val changed = state.withLevel(TimelineLevel.YEARS)

        assertEquals(GallerySection.PLACES, changed.section)
        assertEquals("photo-42", changed.anchorId)
        assertEquals(18, changed.anchorOffset)
    }
}
