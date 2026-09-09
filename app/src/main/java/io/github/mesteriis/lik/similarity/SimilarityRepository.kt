package io.github.mesteriis.lik.similarity

import android.content.Context
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.TrashRepository
import io.github.mesteriis.lik.imports.PhotoLibrary

data class ComparisonPhoto(val media:MediaRecord,val capabilities:ComparisonCapabilities)
data class ComparisonPair(val relation:SimilarityRelationRecord,val left:ComparisonPhoto,val right:ComparisonPhoto)

class SimilarityRepository(private val context:Context,private val database:MediaDatabase=MediaDatabase.get(context)) {
    fun pairs(limit:Int=60,offset:Int=0,beforeFinalCheck:()->Unit={}):List<ComparisonPair>{
        val relations=database.similarity().visibleRelations(limit,offset);beforeFinalCheck()
        return relations.mapNotNull{pair(it.leftMediaId,it.rightMediaId)}
    }
    fun pair(left:String,right:String):ComparisonPair?=database.runInTransaction<ComparisonPair?>{
        val ordered=runCatching{MediaPair.ordered(left,right)}.getOrNull()?:return@runInTransaction null
        val relation=database.similarity().visibleRelation(ordered.left,ordered.right)?:return@runInTransaction null
        val l=database.media().get(relation.leftMediaId)?:return@runInTransaction null;val r=database.media().get(relation.rightMediaId)?:return@runInTransaction null
        ComparisonPair(relation,ComparisonPhoto(l,ComparisonCapabilities.forMedia(l,true)),ComparisonPhoto(r,ComparisonCapabilities.forMedia(r,true)))
    }
    fun moveImportedToTrash(mediaId:String):Boolean{
        val row=database.media().get(mediaId)?:return false
        if(!ComparisonCapabilities.forMedia(row,database.similarity().currentSafe(row.mediaId,row.contentRevision,row.accessGrantEpoch)!=null).canMoveToTrash)return false
        return TrashRepository(database,PhotoLibrary.store(context)).trash(setOf(mediaId))==1
    }
}

object SimilarityPublicationGuard{
    fun visible(database:MediaDatabase,relation:SimilarityRelationRecord):Boolean{
        val sql="SELECT COUNT(*) FROM media l JOIN media r ON r.mediaId=? JOIN similarity_relation s ON s.leftMediaId=l.mediaId AND s.rightMediaId=r.mediaId JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure rx ON rx.mediaId=r.mediaId AND rx.contentRevision=r.contentRevision WHERE l.mediaId=? AND l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND r.availability='AVAILABLE' AND r.trashedAt IS NULL AND rx.exposure='SAFE' AND l.contentRevision=? AND l.accessGrantEpoch=? AND r.contentRevision=? AND r.accessGrantEpoch=? AND s.leftRevision=? AND s.rightRevision=? AND s.leftAccessEpoch=? AND s.rightAccessEpoch=? AND s.kind=? AND s.fingerprintVersion=? AND s.distance=?"
        val arguments=arrayOf<Any?>(relation.rightMediaId,relation.leftMediaId,relation.leftRevision,relation.leftAccessEpoch,relation.rightRevision,relation.rightAccessEpoch,relation.leftRevision,relation.rightRevision,relation.leftAccessEpoch,relation.rightAccessEpoch,relation.kind.name,relation.fingerprintVersion,relation.distance)
        return database.openHelper.readableDatabase.query(sql,arguments).use{it.moveToFirst()&&it.getInt(0)==1}
    }
}
