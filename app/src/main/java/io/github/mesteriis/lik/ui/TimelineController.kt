package io.github.mesteriis.lik.ui

import io.github.mesteriis.lik.gallery.GalleryPhoto
import io.github.mesteriis.lik.gallery.GalleryTimeline
import io.github.mesteriis.lik.gallery.TimelineEntry
import io.github.mesteriis.lik.gallery.TimelineLevel
import java.time.ZoneId
import java.util.concurrent.Executor

internal data class TimelineSnapshot(
    val entries: List<TimelineEntry> = emptyList(),
    val level: TimelineLevel = TimelineLevel.DAYS,
)

internal data class TimelineRevision(
    val id: Long,
    val previous: TimelineSnapshot,
    val level: TimelineLevel,
    val entries: List<TimelineEntry>,
)

/** Builds immutable timeline revisions on a worker and drops every revision superseded in flight. */
internal class TimelineController(
    private val executor: Executor,
    private val zoneId: ZoneId,
    private val onReady: (TimelineRevision) -> Unit,
) {
    private val lock = Any()
    private var nextId = 0L
    private var latestId = 0L
    private var latestInput: Pair<List<GalleryPhoto>, TimelineLevel>? = null
    private var published = TimelineSnapshot()
    private var closed = false

    fun submit(photos: List<GalleryPhoto>, level: TimelineLevel): Long? {
        val source = photos.toList()
        val revision = synchronized(lock) {
            if (closed || latestInput == Pair(source, level)) return null
            val id = ++nextId
            latestId = id
            latestInput = Pair(source, level)
            id to published
        }
        executor.execute {
            val entries = GalleryTimeline.build(source, level, zoneId).toList()
            val update = TimelineRevision(revision.first, revision.second, level, entries)
            val deliver = synchronized(lock) { !closed && update.id == latestId }
            if (deliver) onReady(update)
        }
        return revision.first
    }

    /** Returns false when this revision was superseded before its UI publication. */
    fun publish(revision: TimelineRevision): Boolean = synchronized(lock) {
        if (closed || revision.id != latestId || published != revision.previous) return false
        published = TimelineSnapshot(revision.entries, revision.level)
        true
    }

    fun close() = synchronized(lock) { closed = true }
}
