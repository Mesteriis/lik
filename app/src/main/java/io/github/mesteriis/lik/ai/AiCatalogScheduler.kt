package io.github.mesteriis.lik.ai

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.*
import io.github.mesteriis.lik.catalog.CatalogChanges
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.privacy.SensitiveClassifierWorker
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Coalesces catalog/eligibility changes; a change during running work appends a successor.
 * WorkManager persists the chain and startup reconciles any event before enqueue was durable. */
class AiCatalogScheduler(private val context:Context,private val database:MediaDatabase) {
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private var timer:ScheduledFuture<*>?=null
    private var last:Pair<Long,Map<ProfileId,Set<AiFeature>>>?=null
    private val observer=object:InvalidationTracker.Observer("catalog_change_state") {
        override fun onInvalidated(tables:Set<String>)=changed()
    }
    private var modelSubscription:AutoCloseable?=null

    init {
        database.invalidationTracker.addObserver(observer)
        executor.execute {
            var previous:Map<ProfileId,Set<AiFeature>>?=null
            modelSubscription=ModelCatalog.get(context).observe { state ->
                val requested=plans(state)
                if(requested!=previous){previous=requested;changed()}
            }
        }
        changed()
    }

    @Synchronized fun changed() {
        timer?.cancel(false)
        timer=executor.schedule({enqueueLatest()},250,TimeUnit.MILLISECONDS)
    }

    private fun enqueueLatest() {
        val plans=plans(ModelCatalog.get(context).snapshot())
        val current=CatalogChanges.revision(database) to plans
        if(current==last)return
        val constraints=Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
        val screening=OneTimeWorkRequestBuilder<SensitiveClassifierWorker>().setInputData(workDataOf("catalog-pass" to true)).setConstraints(constraints).addTag(TAG).build()
        val requests=plans.flatMap { (profile,features) ->
            buildList<OneTimeWorkRequest> {
                val input=workDataOf("profile" to profile.wire,"manual" to false)
                if(AiFeature.SEARCH in features)add(OneTimeWorkRequestBuilder<AiIndexWorker>().setInputData(input).setConstraints(constraints).addTag(TAG).addTag("ai-index-${profile.wire}").build())
                if(features.any{it==AiFeature.OCR||it==AiFeature.PEOPLE})add(OneTimeWorkRequestBuilder<OcrPeopleIndexWorker>().setInputData(input).setConstraints(constraints).addTag(TAG).addTag("ai-ocr-people-${profile.wire}").build())
            }
        }
        var chain=WorkManager.getInstance(context).beginUniqueWork("ai-catalog-pipeline",ExistingWorkPolicy.APPEND_OR_REPLACE,screening)
        if(requests.isNotEmpty())chain=chain.then(requests)
        chain.enqueue().result.get()
        last=current
    }

    companion object {
        const val TAG="lik-catalog-ai"
        internal fun plans(state:CatalogSnapshot):Map<ProfileId,Set<AiFeature>> = buildMap {
            state.active?.let { put(it,state.enabledFeatures) }
            state.pending?.takeIf { state.profile(it.profile).phase in setOf(ProfilePhase.PREPARING,ProfilePhase.ACTIVE) }
                ?.let { put(it.profile,it.enabled) }
        }.filterValues { it.isNotEmpty() }
    }
}
