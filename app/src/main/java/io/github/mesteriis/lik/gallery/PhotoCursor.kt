package io.github.mesteriis.lik.gallery

class PhotoCursor private constructor(
    val photos: List<GalleryPhoto>,
    private val index: Int,
) {
    constructor(photos: List<GalleryPhoto>, requestedId: String?) : this(
        photos,
        photos.indexOfFirst { it.id == requestedId },
    )

    val current: GalleryPhoto? get() = photos.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index >= 0 && index < photos.lastIndex

    fun move(delta: Int): PhotoCursor = if (photos.isEmpty()) this
        else PhotoCursor(photos, (index + delta).coerceIn(0, photos.lastIndex))

    fun without(id: String): PhotoCursor {
        val removedIndex = photos.indexOfFirst { it.id == id }
        if (removedIndex < 0) return this
        val remaining = photos.filterNot { it.id == id }
        if (remaining.isEmpty()) return PhotoCursor(emptyList(), -1)
        val nextIndex = when {
            removedIndex < index -> index - 1
            removedIndex == index -> index.coerceAtMost(remaining.lastIndex)
            else -> index
        }
        return PhotoCursor(remaining, nextIndex)
    }
}
