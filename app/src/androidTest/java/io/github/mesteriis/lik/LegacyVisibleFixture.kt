package io.github.mesteriis.lik

import android.content.Context
import androidx.room.InvalidationTracker
import io.github.mesteriis.lik.ai.AiExposure
import io.github.mesteriis.lik.ai.AiMediaExposureRecord
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.privacy.SensitiveDecision
import io.github.mesteriis.lik.privacy.SensitiveManualRecord

/** Old gallery tests predate quarantine; make their ordinary fixture rows explicitly safe. */
class LegacyVisibleFixture(context: Context) : AutoCloseable {
    private val database = MediaDatabase.get(context)
    private val observer = object : InvalidationTracker.Observer("media") {
        override fun onInvalidated(tables: Set<String>) = markCurrentSafe()
    }

    init {
        database.invalidationTracker.addObserver(observer)
        markCurrentSafe()
    }

    fun markCurrentSafe() {
        database.runInTransaction {
            database.media().available().forEach { row ->
                database.sensitiveMedia().saveManual(SensitiveManualRecord(row.mediaId,row.contentRevision,SensitiveDecision.SAFE,System.currentTimeMillis()))
                database.ocrPeople().saveExposure(
                    AiMediaExposureRecord(row.mediaId, row.contentRevision, AiExposure.SAFE, System.currentTimeMillis()),
                )
            }
        }
    }

    override fun close() = database.invalidationTracker.removeObserver(observer)
}
