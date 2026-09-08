package io.github.mesteriis.lik.ui

import java.util.PriorityQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

internal data class ThumbnailKey(
    val photoId: String,
    val sourceRevision: Long,
    val targetSize: Int,
    val accessEpoch: Long,
)

internal enum class ThumbnailPriority { PREFETCH, VISIBLE }
internal enum class ThumbnailRequestDisposition { CACHED, QUEUED, COALESCED, REJECTED }

internal fun thumbnailPriority(isAttached: Boolean) =
    if (isAttached) ThumbnailPriority.VISIBLE else ThumbnailPriority.PREFETCH

internal class ThumbnailAccessEpoch {
    var current = 0L
        private set

    fun refresh() { current++ }
    fun accepts(key: ThumbnailKey) = key.accessEpoch == current
}

internal data class ThumbnailResult<T>(
    val value: T? = null,
    val cancelled: Boolean = false,
)

internal data class ThumbnailLoaderSnapshot(
    val active: Int,
    val queued: Int,
    val coalesced: Int,
    val cancelled: Int,
    val rejected: Int,
)

internal interface ThumbnailCache<T> {
    fun get(key: ThumbnailKey): T?
    fun put(key: ThumbnailKey, value: T)
    fun remove(key: ThumbnailKey)
    fun keys(): Set<ThumbnailKey>
    fun clear()
}

internal class MapThumbnailCache<T> : ThumbnailCache<T> {
    private val values = linkedMapOf<ThumbnailKey, T>()
    override fun get(key: ThumbnailKey) = values[key]
    override fun put(key: ThumbnailKey, value: T) { values[key] = value }
    override fun remove(key: ThumbnailKey) { values.remove(key) }
    override fun keys() = values.keys.toSet()
    override fun clear() { values.clear() }
}

internal class ThumbnailRequest<T> internal constructor(
    val disposition: ThumbnailRequestDisposition,
    private val cancelRequest: () -> Unit,
) {
    fun cancel() = cancelRequest()
}

/**
 * A fixed two-worker thumbnail scheduler. The pending queue is explicitly bounded; overflow either
 * replaces queued prefetch work with visible work or rejects the request without inline decoding.
 */
internal class ThumbnailLoader<T>(
    private val executor: Executor,
    private val cache: ThumbnailCache<T>,
    private val queueCapacity: Int,
) {
    init { require(queueCapacity > 0) }

    private data class Listener<T>(val id: Long, val callback: (ThumbnailResult<T>) -> Unit)
    private class Work<T>(
        val key: ThumbnailKey,
        var priority: ThumbnailPriority,
        val order: Long,
        val decode: () -> T?,
    ) {
        val listeners = linkedMapOf<Long, Listener<T>>()
        var running = false
    }

    private val lock = Any()
    private var nextOrder = 0L
    private var nextListenerId = 0L
    private var active = 0
    private var coalesced = 0
    private var cancelled = 0
    private var rejected = 0
    private var closed = false
    private val workByKey = mutableMapOf<ThumbnailKey, Work<T>>()
    private val queued = PriorityQueue<Work<T>>(compareByDescending<Work<T>> { it.priority.ordinal }.thenBy { it.order })

    fun load(
        key: ThumbnailKey,
        priority: ThumbnailPriority,
        decode: () -> T?,
        onResult: (ThumbnailResult<T>) -> Unit,
    ): ThumbnailRequest<T> {
        var cached: T? = null
        var evicted: List<Listener<T>> = emptyList()
        var listenerId = NO_LISTENER
        val result = synchronized(lock) {
            if (closed) return@synchronized ThumbnailRequestDisposition.REJECTED
            cached = cache.get(key)
            if (cached != null) return@synchronized ThumbnailRequestDisposition.CACHED
            val listener = Listener(++nextListenerId, onResult)
            listenerId = listener.id
            val existing = workByKey[key]
            if (existing != null) {
                existing.listeners[listener.id] = listener
                if (!existing.running && priority.ordinal > existing.priority.ordinal) {
                    queued.remove(existing)
                    existing.priority = priority
                    queued.add(existing)
                }
                coalesced++
                return@synchronized ThumbnailRequestDisposition.COALESCED
            }
            if (queued.size == queueCapacity) {
                val evictedWork = queued.filter { it.priority == ThumbnailPriority.PREFETCH }.maxByOrNull { it.order }
                if (priority != ThumbnailPriority.VISIBLE || evictedWork?.priority != ThumbnailPriority.PREFETCH) {
                    rejected++
                    return@synchronized ThumbnailRequestDisposition.REJECTED
                }
                queued.remove(evictedWork)
                workByKey.remove(evictedWork.key)
                evicted = evictedWork.listeners.values.toList()
                cancelled += evicted.size
            }
            val work = Work(key, priority, ++nextOrder, decode)
            work.listeners[listener.id] = listener
            workByKey[key] = work
            queued.add(work)
            startWorkersLocked()
            ThumbnailRequestDisposition.QUEUED
        }
        if (cached != null) onResult(ThumbnailResult(value = cached))
        evicted.forEach { it.callback(ThumbnailResult(cancelled = true)) }
        return ThumbnailRequest(result) { cancel(key, listenerId) }
    }

    fun invalidate(predicate: (ThumbnailKey) -> Boolean) = synchronized(lock) {
        cache.keys().filter(predicate).forEach(cache::remove)
    }

    fun snapshot(): ThumbnailLoaderSnapshot = synchronized(lock) {
        ThumbnailLoaderSnapshot(active, queued.size, coalesced, cancelled, rejected)
    }

    fun close() {
        val callbacks = synchronized(lock) {
            if (closed) return
            closed = true
            val pending = queued.flatMap { it.listeners.values }
            cancelled += pending.size
            queued.clear()
            workByKey.clear()
            cache.clear()
            pending
        }
        callbacks.forEach { it.callback(ThumbnailResult(cancelled = true)) }
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun cancel(key: ThumbnailKey, listenerId: Long) {
        synchronized(lock) {
            val work = workByKey[key] ?: return
            val listener = work.listeners.remove(listenerId) ?: return
            cancelled++
            if (!work.running && work.listeners.isEmpty()) {
                queued.remove(work)
                workByKey.remove(key)
            }
        }
    }

    private fun startWorkersLocked() {
        while (!closed && active < WORKER_COUNT && queued.isNotEmpty()) {
            val work = queued.remove()
            work.running = true
            active++
            executor.execute { run(work) }
        }
    }

    private fun run(work: Work<T>) {
        val result = try { work.decode() } catch (_: Exception) { null }
        val listeners = synchronized(lock) {
            active--
            workByKey.remove(work.key)
            if (!closed && result != null) cache.put(work.key, result)
            val deliver = if (closed) emptyList() else work.listeners.values.toList()
            startWorkersLocked()
            deliver
        }
        listeners.forEach { it.callback(ThumbnailResult(value = result)) }
    }

    private companion object {
        const val WORKER_COUNT = 2
        const val NO_LISTENER = -1L
    }
}
