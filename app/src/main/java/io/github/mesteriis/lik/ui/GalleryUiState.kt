package io.github.mesteriis.lik.ui

import io.github.mesteriis.lik.gallery.TimelineLevel

enum class GallerySection { FEED, ALBUMS, PLACES, PEOPLE, MORE }

data class GalleryUiState(
    val level: TimelineLevel = TimelineLevel.DAYS,
    val section: GallerySection = GallerySection.FEED,
    val anchorId: String? = null,
    val anchorOffset: Int = 0,
) {
    fun zoomIn() = withLevel(level.closer())
    fun zoomOut() = withLevel(level.farther())
    fun withLevel(value: TimelineLevel) = copy(level = value)
}
