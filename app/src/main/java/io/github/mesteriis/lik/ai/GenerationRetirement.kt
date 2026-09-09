package io.github.mesteriis.lik.ai

import android.content.Context
import io.github.mesteriis.lik.catalog.MediaDatabase
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Pins a generation while search reads its Room rows and native files. */
object GenerationUseCoordinator {
    private data class Entry(val lock: ReentrantReadWriteLock = ReentrantReadWriteLock(true), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    fun <T> read(id: String, block: () -> T): T {
        val entry = borrow(id)
        val lock = entry.lock.readLock()
        try { lock.lockInterruptibly() } catch (error: Throwable) { release(id, entry); throw error }
        return try { block() } finally { lock.unlock(); release(id, entry) }
    }

    fun <T> exclusive(id: String, block: () -> T): T {
        val entry = borrow(id)
        val lock = entry.lock.writeLock()
        try { lock.lockInterruptibly() } catch (error: Throwable) { release(id, entry); throw error }
        return try { block() } finally { lock.unlock(); release(id, entry) }
    }

    private fun borrow(id: String) = synchronized(entries) {
        entries.getOrPut(id) { Entry() }.also { it.users++ }
    }

    private fun release(id: String, entry: Entry) = synchronized(entries) {
        check(entry.users > 0)
        entry.users--
        if (entry.users == 0) entries.remove(id, entry)
    }
}

enum class GenerationRetirementStep { JOURNALED, ROOM_REMOVED, NATIVE_REMOVED }

/** Durable, idempotent removal. Catalog authority is rechecked while holding the exclusive lease. */
object GenerationRetirement {
    @Volatile internal var afterStepForTests: ((GenerationRetirementStep, String) -> Unit)? = null

    fun journal(root: File, profile: ProfileId, ids: Set<String>) {
        if (ids.isEmpty()) return
        GenerationRemovalJournal(root).begin(profile, ids)
        ids.forEach { afterStepForTests?.invoke(GenerationRetirementStep.JOURNALED, it) }
    }

    fun drain(
        context: Context,
        profile: ProfileId,
        ids: Set<String>,
        catalog: ModelCatalog = ModelCatalog.get(context),
        database: MediaDatabase = MediaDatabase.get(context),
    ) {
        val journal = GenerationRemovalJournal(File(context.filesDir, "ai"))
        ids.sorted().forEach { id ->
            GenerationUseCoordinator.exclusive(id) {
                val removed = catalog.retireIfUnretained(id) {
                    database.runInTransaction { database.aiIndexes().deleteGenerations(setOf(id)) }
                    afterStepForTests?.invoke(GenerationRetirementStep.ROOM_REMOVED, id)
                    NativeIndexFiles.remove(context, id)
                    afterStepForTests?.invoke(GenerationRetirementStep.NATIVE_REMOVED, id)
                }
                if (removed) journal.complete(profile, setOf(id))
            }
        }
    }

    fun retire(
        context: Context,
        profile: ProfileId,
        ids: Set<String>,
        catalog: ModelCatalog = ModelCatalog.get(context),
        database: MediaDatabase = MediaDatabase.get(context),
    ) {
        journal(File(context.filesDir, "ai"), profile, ids)
        drain(context, profile, ids, catalog, database)
    }
}
