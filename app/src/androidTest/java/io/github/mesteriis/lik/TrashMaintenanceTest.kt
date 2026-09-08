package io.github.mesteriis.lik

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.imports.PhotoLibrary
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

class TrashMaintenanceTest {
    @Test fun workerRetriesFailedUnlinkAndCompletesDurableClaimOnNextRun() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = MediaDatabase.get(context)
        val store = PhotoLibrary.store(context)
        val id = "b".repeat(64)
        val file = store.fileFor(id)
        val executor = Executors.newSingleThreadExecutor()
        try {
            synchronized(store) {
                file.deleteRecursively(); file.mkdirs(); File(file, "busy").writeText("busy")
                db.media().upsert(MediaRecord(id, MediaSource.GOOGLE_IMPORT, id, privateFileId = id,
                    availability = MediaAvailability.TRASHED, trashedAt = 1, lastSeenAt = 1))
                val worker = TestWorkerBuilder.from(context, TrashMaintenance::class.java, executor).build()
                assertEquals(ListenableWorker.Result.retry(), worker.doWork())
                assertEquals(MediaAvailability.PURGING, db.media().get(id)!!.availability)
                file.deleteRecursively()
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
                assertNull(db.media().get(id))
            }
        } finally {
            synchronized(store) { file.deleteRecursively(); db.media().claimPurge(setOf(id)); db.media().finishPurge(id) }
            executor.shutdownNow()
        }
    }
}
