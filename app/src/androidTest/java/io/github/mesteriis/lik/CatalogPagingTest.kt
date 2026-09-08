package io.github.mesteriis.lik

import android.content.Context
import androidx.paging.AsyncPagingDataDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.gallery.TimelineEntry
import io.github.mesteriis.lik.gallery.TimelineLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class CatalogPagingTest {
    @Test fun startsAtDistantIdentityAndDropsOldPagesWhileScrolling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        try {
            db.runInTransaction {
                repeat(2000) { index ->
                    val id = index.toString().padStart(64, '0')
                    db.media().upsert(MediaRecord(id, MediaSource.GOOGLE_IMPORT, id, addedAt = index.toLong(),
                        lastSeenAt = 1).withPeriods(ZoneId.of("UTC")))
                }
            }
            val anchor = 500.toString().padStart(64, '0')
            val differ = AsyncPagingDataDiffer(object : DiffUtil.ItemCallback<TimelineEntry>() {
                override fun areItemsTheSame(a: TimelineEntry, b: TimelineEntry) = a.stableKey == b.stableKey
                override fun areContentsTheSame(a: TimelineEntry, b: TimelineEntry) = a == b
            }, object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) = Unit
                override fun onRemoved(position: Int, count: Int) = Unit
                override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
            })
            val job = launch(Dispatchers.Main) {
                CatalogPaging(db.media(), context.getString(R.string.timeline_undated)) { File(context.cacheDir, it) }.flow(TimelineLevel.PHOTO, anchor, 0)
                    .collectLatest { differ.submitData(it) }
            }
            try {
                withTimeout(10000) { while (differ.itemCount == 0) delay(20) }
                assertEquals(anchor, (differ.snapshot().items.first() as TimelineEntry.Photo).photo.id)
                assertEquals(1499, (differ.snapshot().items.first() as TimelineEntry.Photo).catalogIndex)
                repeat(5) {
                    val lastId = differ.snapshot().items.last().stableKey
                    withContext(Dispatchers.Main) { differ.getItem(differ.itemCount - 1) }
                    withTimeout(10000) { while (differ.snapshot().items.last().stableKey == lastId) delay(20) }
                    assertTrue("Retained ${differ.itemCount} rows", differ.itemCount <= 180)
                }
                assertFalse(differ.snapshot().items.any { it.stableKey == "photo:$anchor" })
            } finally { job.cancelAndJoin() }
        } finally { db.close() }
    }
}
