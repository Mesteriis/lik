package io.github.mesteriis.lik.ui

import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.PhotoSource
import io.github.mesteriis.lik.gallery.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryAccessAndAnchorTest {
    @Test fun permissionSnapshotDistinguishesFullPartialDeniedAndPermanentDenial() {
        assertEquals(
            DevicePhotoAccess.FULL,
            DevicePhotoAccess.fromPermissions(fullGranted = true, selectedGranted = false, requestedBefore = false, shouldShowRationale = false),
        )
        assertEquals(
            DevicePhotoAccess.PARTIAL,
            DevicePhotoAccess.fromPermissions(fullGranted = false, selectedGranted = true, requestedBefore = true, shouldShowRationale = true),
        )
        assertEquals(
            DevicePhotoAccess.DENIED,
            DevicePhotoAccess.fromPermissions(fullGranted = false, selectedGranted = false, requestedBefore = false, shouldShowRationale = false),
        )
        assertEquals(
            DevicePhotoAccess.PERMANENTLY_DENIED,
            DevicePhotoAccess.fromPermissions(fullGranted = false, selectedGranted = false, requestedBefore = true, shouldShowRationale = false),
        )
    }

    @Test fun screenStateSeparatesLoadingEmptyPartialDeniedPermanentAndSourceError() {
        assertEquals(GalleryScreenState.Loading(DevicePhotoAccess.FULL), GalleryScreenState.resolve(DevicePhotoAccess.FULL, scanning = true, photos = 0, imports = 0))
        assertEquals(GalleryScreenState.Empty(DevicePhotoAccess.FULL), GalleryScreenState.resolve(DevicePhotoAccess.FULL, scanning = false, photos = 0, imports = 0))
        assertEquals(GalleryScreenState.Partial(3), GalleryScreenState.resolve(DevicePhotoAccess.PARTIAL, scanning = false, photos = 3, imports = 1))
        assertEquals(GalleryScreenState.Denied(1), GalleryScreenState.resolve(DevicePhotoAccess.DENIED, scanning = false, photos = 1, imports = 1))
        assertEquals(GalleryScreenState.PermanentlyDenied(2), GalleryScreenState.resolve(DevicePhotoAccess.PERMANENTLY_DENIED, scanning = false, photos = 2, imports = 2))
        assertEquals(GalleryScreenState.SourceError(DevicePhotoAccess.PARTIAL, 1), GalleryScreenState.resolve(DevicePhotoAccess.PARTIAL, scanning = false, photos = 1, imports = 1, sourceError = true))
    }

    @Test fun focusAnchorChoosesNearestPhotoAndKeepsFocusRelativeOffset() {
        val anchor = GalleryAnchor.capture(
            focusY = 175,
            candidates = listOf(
                AnchorCandidate("newest", chronologicalIndex = 0, top = 20, bottom = 100),
                AnchorCandidate("focused", chronologicalIndex = 1, top = 130, bottom = 230),
                AnchorCandidate("older", chronologicalIndex = 2, top = 250, bottom = 330),
            ),
        )

        assertEquals(GalleryAnchor("focused", chronologicalIndex = 1, relativeOffset = 45), anchor)
    }

    @Test fun missingAnchorFallsBackToTheNearestChronologicalPhoto() {
        val anchor = GalleryAnchor("removed", chronologicalIndex = 2, relativeOffset = -18)

        assertEquals("third", anchor.resolveId(listOf("newest", "second", "third", "oldest")))
        assertEquals("only", anchor.resolveId(listOf("only")))
    }

    @Test fun missingAnchorReturnsNullWhenTheLibraryIsNowEmpty() {
        val anchor = GalleryAnchor("removed", chronologicalIndex = 2, relativeOffset = -18)

        assertEquals(null, anchor.resolveId(emptyList()))
    }

    @Test fun pinchCandidatesExcludeHeadersAndKeepRenderedPhotos() {
        val photo = GalleryPhoto("photo", PhotoSource.GOOGLE_IMPORT)

        assertEquals(null, renderedPhotoAnchorCandidate(TimelineEntry.Header("day", "Today", "photo"), 0, 20, 100))
        assertEquals(
            AnchorCandidate("photo", 4, 20, 100),
            renderedPhotoAnchorCandidate(TimelineEntry.Photo(photo, 0, 1), 4, 20, 100),
        )
    }
}
