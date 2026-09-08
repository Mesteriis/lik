package io.github.mesteriis.lik.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySelectionTest {
    @Test fun selectionUsesPhotoIdsInsteadOfPositions() {
        val selected = GallerySelection().toggle("photo-a").toggle("photo-c")

        assertEquals(setOf("photo-a", "photo-c"), selected.ids)
        assertEquals(setOf("photo-a", "photo-c"), selected.retainAvailable(setOf("photo-c", "photo-a", "photo-b")).ids)
    }

    @Test fun missingPhotosAreRemovedAndToggleCanDeselect() {
        val selected = GallerySelection(setOf("photo-a", "photo-b"))

        assertEquals(setOf("photo-b"), selected.retainAvailable(setOf("photo-b", "photo-c")).ids)
        assertEquals(setOf("photo-a"), selected.toggle("photo-b").ids)
    }
}
