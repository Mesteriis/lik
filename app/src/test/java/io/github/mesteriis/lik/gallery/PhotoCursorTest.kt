package io.github.mesteriis.lik.gallery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCursorTest {
    private val photos = listOf("a", "b", "c").map {
        GalleryPhoto(it, PhotoSource.GOOGLE_IMPORT, file = File("$it.image"))
    }

    @Test fun cursorStartsAtRequestedPhotoAndMovesWithinBounds() {
        val cursor = PhotoCursor(photos, "b")

        assertEquals("b", cursor.current?.id)
        assertTrue(cursor.hasPrevious)
        assertTrue(cursor.hasNext)
        assertEquals("c", cursor.move(1).current?.id)
        assertEquals("a", cursor.move(-1).move(-1).current?.id)
    }

    @Test fun missingRequestedPhotoAndEmptyCursorHaveNoCurrentItem() {
        val missing = PhotoCursor(photos, "missing")
        val empty = PhotoCursor(emptyList(), "missing")

        assertEquals(null, missing.current)
        assertFalse(missing.hasPrevious)
        assertFalse(missing.hasNext)
        assertFalse(empty.hasPrevious)
        assertFalse(empty.hasNext)
        assertEquals(null, empty.current)
    }

    @Test fun removingCurrentChoosesNextThenPrevious() {
        assertEquals("c", PhotoCursor(photos, "b").without("b").current?.id)
        assertEquals("b", PhotoCursor(photos, "c").without("c").current?.id)
        assertEquals(null, PhotoCursor(listOf(photos.first()), "a").without("a").current)
    }
}
