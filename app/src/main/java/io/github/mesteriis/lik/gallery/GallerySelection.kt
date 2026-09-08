package io.github.mesteriis.lik.gallery

data class GallerySelection(val ids: Set<String> = emptySet()) {
    fun toggle(id: String): GallerySelection =
        copy(ids = if (id in ids) ids - id else ids + id)

    fun retainAvailable(available: Set<String>): GallerySelection =
        copy(ids = ids intersect available)
}
