package io.github.mesteriis.lik.catalog

import android.os.CancellationSignal
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.ZoneId

data class VolumeState(val version: String, val generation: Long, val fullAccess: Boolean = true)
@Entity(tableName = "volume_checkpoint")
data class VolumeCheckpoint(@PrimaryKey val volume: String, val version: String, val generation: Long, val fullAccess: Boolean)

interface MediaInventory {
    fun volumes(): Set<String>
    fun state(volume: String): VolumeState
    fun changed(volume: String, state: VolumeState, after: Long, signal: CancellationSignal, emit: (List<MediaRecord>) -> Unit)
    fun visibleIds(volume: String, state: VolumeState, signal: CancellationSignal, emit: (List<String>) -> Unit)
}

/** Bounded provider batches; a cancelled/unstable scan never commits absence or advances a checkpoint. */
class MediaScanner(private val database: MediaDatabase, private val zone: ZoneId) {
    fun scan(source: MediaInventory, fullAccess: Boolean, signal: CancellationSignal) {
        repeat(3) { attempt ->
            try {
                scanOnce(source, fullAccess, signal)
                return
            } catch (changed: InventoryChanged) {
                if (attempt == 2) throw changed
                signal.throwIfCanceled()
            }
        }
    }

    private fun scanOnce(source: MediaInventory, fullAccess: Boolean, signal: CancellationSignal) {
        val dao = database.media()
        val stamp = java.util.UUID.randomUUID().toString()
        val observedAt = System.currentTimeMillis()
        val volumes = source.volumes()
        database.runInTransaction {
            signal.throwIfCanceled()
            for (volume in volumes.sorted()) {
                val start = source.state(volume)
                check(!fullAccess || start.fullAccess) { "MediaStore permission changed before scan" }
                val previous = dao.checkpoint(volume)
                val after = previous?.takeIf { it.version == start.version && it.generation <= start.generation &&
                    it.fullAccess && fullAccess }?.generation ?: 0
                source.changed(volume, start, after, signal) { batch ->
                    signal.throwIfCanceled()
                    batch.forEach { incoming ->
                        val old = dao.get(incoming.mediaId)
                        val accessEpoch = old?.let {
                            if (it.availability != MediaAvailability.AVAILABLE) Math.addExact(it.accessGrantEpoch, 1)
                            else it.accessGrantEpoch
                        } ?: incoming.accessGrantEpoch
                        val record = if (old?.contentRevision == incoming.contentRevision) incoming.copy(
                            exifRevision = old.exifRevision, exifOrientation = old.exifOrientation,
                            takenAt = if (old.dateSource == MediaDateSource.EXIF) old.takenAt else incoming.takenAt,
                            dateSource = if (old.dateSource == MediaDateSource.EXIF) old.dateSource else incoming.dateSource,
                            dateOffsetSeconds = old.dateOffsetSeconds,
                        ) else incoming
                        dao.upsert(record.copy(lastSeenAt = observedAt, accessGrantEpoch = accessEpoch, scanMarker = stamp).withPeriods(zone))
                    }
                }
                source.visibleIds(volume, start, signal) { ids ->
                    signal.throwIfCanceled()
                    ids.forEach { dao.markScanSeen(it, stamp, observedAt) }
                }
                signal.throwIfCanceled()
                val end = source.state(volume)
                check(start.fullAccess == end.fullAccess) { "MediaStore permission changed during scan" }
                if (start.version != end.version || start.generation != end.generation) throw InventoryChanged()
                dao.reconcileVolume(volume, start.version, stamp, if (fullAccess) MediaAvailability.MISSING else MediaAvailability.INACCESSIBLE)
                dao.saveCheckpoint(VolumeCheckpoint(volume, start.version, start.generation, fullAccess))
            }
            dao.detachedVolumes(volumes.toList())
            signal.throwIfCanceled()
        }
    }

    private class InventoryChanged : IllegalStateException("MediaStore changed during scan")
}
