package io.github.mesteriis.lik.similarity

import io.github.mesteriis.lik.ai.AiMediaExposureRecord
import io.github.mesteriis.lik.catalog.MediaRecord

/** Fields whose actual value changes can alter fingerprint bytes or visible similarity membership. */
object SimilarityDomainRevision {
    val MEDIA_UPDATE_COLUMNS=listOf("mediaId","source","sourceKey","volumeName","volumeVersion","generationAdded","contentUri","privateFileId","contentRevision","availability","accessGrantEpoch","trashedAt")
    val EXPOSURE_UPDATE_COLUMNS=listOf("mediaId","contentRevision","exposure")
    val mediaUpdateWhen=MEDIA_UPDATE_COLUMNS.joinToString(" OR "){"OLD.$it IS NOT NEW.$it"}
    val exposureUpdateWhen=EXPOSURE_UPDATE_COLUMNS.joinToString(" OR "){"OLD.$it IS NOT NEW.$it"}

    fun mediaChanged(old:MediaRecord,new:MediaRecord)=listOf(
        old.mediaId,old.source,old.sourceKey,old.volumeName,old.volumeVersion,old.generationAdded,old.contentUri,old.privateFileId,
        old.contentRevision,old.availability,old.accessGrantEpoch,old.trashedAt,
    )!=listOf(
        new.mediaId,new.source,new.sourceKey,new.volumeName,new.volumeVersion,new.generationAdded,new.contentUri,new.privateFileId,
        new.contentRevision,new.availability,new.accessGrantEpoch,new.trashedAt,
    )

    fun exposureChanged(old:AiMediaExposureRecord,new:AiMediaExposureRecord)=
        old.mediaId!=new.mediaId||old.contentRevision!=new.contentRevision||old.exposure!=new.exposure
}
