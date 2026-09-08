package io.github.mesteriis.lik.ui

/** The device-library permission state. Private Lik imports do not depend on this state. */
enum class DevicePhotoAccess {
    FULL,
    PARTIAL,
    DENIED,
    PERMANENTLY_DENIED;

    val canReadDevicePhotos get() = this == FULL || this == PARTIAL

    companion object {
        fun fromPermissions(
            fullGranted: Boolean,
            selectedGranted: Boolean,
            requestedBefore: Boolean,
            shouldShowRationale: Boolean,
        ) = when {
            fullGranted -> FULL
            selectedGranted -> PARTIAL
            requestedBefore && !shouldShowRationale -> PERMANENTLY_DENIED
            else -> DENIED
        }
    }
}

/** A rendering state that keeps permission and source failures distinct from an empty library. */
sealed interface GalleryScreenState {
    data class Loading(val access: DevicePhotoAccess) : GalleryScreenState
    data class Empty(val access: DevicePhotoAccess) : GalleryScreenState
    data class Content(val count: Int) : GalleryScreenState
    data class Partial(val count: Int) : GalleryScreenState
    data class Denied(val importedCount: Int) : GalleryScreenState
    data class PermanentlyDenied(val importedCount: Int) : GalleryScreenState
    data class SourceError(val access: DevicePhotoAccess, val importedCount: Int) : GalleryScreenState

    companion object {
        fun resolve(
            access: DevicePhotoAccess,
            scanning: Boolean,
            photos: Int,
            imports: Int,
            sourceError: Boolean = false,
        ): GalleryScreenState = when {
            scanning -> Loading(access)
            sourceError -> SourceError(access, imports)
            access == DevicePhotoAccess.PERMANENTLY_DENIED -> PermanentlyDenied(imports)
            access == DevicePhotoAccess.DENIED -> Denied(imports)
            access == DevicePhotoAccess.PARTIAL -> Partial(photos)
            photos == 0 -> Empty(access)
            else -> Content(photos)
        }
    }
}

data class AnchorCandidate(
    val photoId: String,
    val chronologicalIndex: Int,
    val top: Int,
    val bottom: Int,
)

/** A photo identity plus the point within it that should remain under the user's focus. */
data class GalleryAnchor(
    val photoId: String,
    val chronologicalIndex: Int,
    val relativeOffset: Int,
) {
    fun resolveId(currentChronologicalIds: List<String>): String? {
        if (photoId in currentChronologicalIds) return photoId
        return currentChronologicalIds.getOrNull(chronologicalIndex.coerceIn(0, currentChronologicalIds.lastIndex))
    }

    companion object {
        fun capture(focusY: Int, candidates: List<AnchorCandidate>): GalleryAnchor? {
            val nearest = candidates.minByOrNull { candidate ->
                kotlin.math.abs((candidate.top + candidate.bottom) / 2 - focusY)
            } ?: return null
            return GalleryAnchor(nearest.photoId, nearest.chronologicalIndex, focusY - nearest.top)
        }
    }
}
