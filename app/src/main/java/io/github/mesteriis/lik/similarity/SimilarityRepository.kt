package io.github.mesteriis.lik.similarity

import android.content.Context
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaRecord
import io.github.mesteriis.lik.catalog.TrashRepository
import io.github.mesteriis.lik.imports.PhotoLibrary

data class ComparisonPhoto(val media:MediaRecord,val capabilities:ComparisonCapabilities)
data class ComparisonPair(val relation:SimilarityRelationRecord,val left:ComparisonPhoto,val right:ComparisonPhoto)

class SimilarityRepository(private val context:Context,private val database:MediaDatabase=MediaDatabase.get(context)) {
    fun visualPairs(limit:Int=60,offset:Int=0,beforeFinalCheck:()->Unit={}):List<ComparisonPair>{
        val relations=database.similarity().visibleRelations(limit,offset);beforeFinalCheck()
        return relations.mapNotNull{pair(it.leftMediaId,it.rightMediaId)}.filter{it.relation.kind==SimilarityKind.VISUAL}
    }
    fun exactGroups(limit:Int=60,offset:Int=0)=database.similarity().exactGroups(limit,offset)
    fun exactMembers(sha256:String,after:String="",limit:Int=60)=database.similarity().exactMembers(sha256,after,limit)
    fun progress()=database.similarity().progress()

    fun pair(left:String,right:String):ComparisonPair?=database.runInTransaction<ComparisonPair?>{
        val ordered=runCatching{MediaPair.ordered(left,right)}.getOrNull()?:return@runInTransaction null
        val dao=database.similarity();val l=database.media().get(ordered.left)?:return@runInTransaction null;val r=database.media().get(ordered.right)?:return@runInTransaction null
        val relation=dao.exactPairSha(ordered.left,ordered.right)?.let{
            val lf=dao.fingerprint(ordered.left)?:return@let null;val rf=dao.fingerprint(ordered.right)?:return@let null
            val distance=runCatching{PerceptualFingerprintV2.distance(lf.perceptualBits,rf.perceptualBits)}.getOrDefault(0)
            SimilarityRelationRecord(ordered.left,ordered.right,l.contentRevision,r.contentRevision,l.accessGrantEpoch,r.accessGrantEpoch,SimilarityKind.EXACT,PerceptualFingerprintV2.VERSION,distance,System.currentTimeMillis())
        }?:dao.visibleRelation(ordered.left,ordered.right)?:return@runInTransaction null
        ComparisonPair(relation,ComparisonPhoto(l,ComparisonCapabilities.forMedia(l,true)),ComparisonPhoto(r,ComparisonCapabilities.forMedia(r,true)))
    }

    fun moveImportedToTrash(mediaId:String):Boolean{
        val row=database.media().get(mediaId)?:return false
        if(!ComparisonCapabilities.forMedia(row,database.similarity().currentSafe(row.mediaId,row.contentRevision,row.accessGrantEpoch)!=null).canMoveToTrash)return false
        return TrashRepository(database,PhotoLibrary.store(context)).trash(setOf(mediaId))==1
    }
    fun moveImportedToTrash(relation:SimilarityRelationRecord,mediaId:String):Boolean{
        if(mediaId!=relation.leftMediaId&&mediaId!=relation.rightMediaId)return false
        if(!SimilarityPublicationGuard.visible(database,relation))return false
        return moveImportedToTrash(mediaId)
    }
}

object SimilarityPublicationGuard{
    fun visible(database:MediaDatabase,relation:SimilarityRelationRecord):Boolean{
        val exactArguments=arrayOf<Any?>(relation.rightMediaId,relation.leftMediaId,relation.leftRevision,relation.leftAccessEpoch,relation.rightRevision,relation.rightAccessEpoch)
        val exact="SELECT COUNT(*) FROM media l JOIN media r ON r.mediaId=? JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure rx ON rx.mediaId=r.mediaId AND rx.contentRevision=r.contentRevision JOIN content_fingerprint lf ON lf.mediaId=l.mediaId JOIN content_fingerprint rf ON rf.mediaId=r.mediaId AND rf.sha256=lf.sha256 WHERE l.mediaId=? AND l.contentRevision=? AND l.accessGrantEpoch=? AND r.contentRevision=? AND r.accessGrantEpoch=? AND lf.contentRevision=l.contentRevision AND lf.accessEpoch=l.accessGrantEpoch AND rf.contentRevision=r.contentRevision AND rf.accessEpoch=r.accessGrantEpoch AND l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND r.availability='AVAILABLE' AND r.trashedAt IS NULL AND rx.exposure='SAFE'"
        if(relation.kind==SimilarityKind.EXACT)return database.openHelper.readableDatabase.query(exact,exactArguments).use{it.moveToFirst()&&it.getInt(0)==1}
        if(relation.fingerprintVersion!=PerceptualFingerprintV2.VERSION)return false
        val baseArguments=(exactArguments.asList()+listOf<Any?>(PerceptualFingerprintV2.VERSION,PerceptualFingerprintV2.VERSION)).toTypedArray()
        val visual="SELECT COUNT(*) FROM media l JOIN media r ON r.mediaId=? JOIN similarity_relation s ON s.leftMediaId=l.mediaId AND s.rightMediaId=r.mediaId JOIN ai_media_exposure lx ON lx.mediaId=l.mediaId AND lx.contentRevision=l.contentRevision JOIN ai_media_exposure rx ON rx.mediaId=r.mediaId AND rx.contentRevision=r.contentRevision JOIN content_fingerprint lf ON lf.mediaId=l.mediaId JOIN content_fingerprint rf ON rf.mediaId=r.mediaId WHERE l.mediaId=? AND l.contentRevision=? AND l.accessGrantEpoch=? AND r.contentRevision=? AND r.accessGrantEpoch=? AND lf.perceptualVersion=? AND rf.perceptualVersion=? AND lf.contentRevision=l.contentRevision AND lf.accessEpoch=l.accessGrantEpoch AND rf.contentRevision=r.contentRevision AND rf.accessEpoch=r.accessGrantEpoch AND l.availability='AVAILABLE' AND l.trashedAt IS NULL AND lx.exposure='SAFE' AND r.availability='AVAILABLE' AND r.trashedAt IS NULL AND rx.exposure='SAFE' AND s.kind='VISUAL' AND s.fingerprintVersion=? AND s.leftRevision=? AND s.rightRevision=? AND s.leftAccessEpoch=? AND s.rightAccessEpoch=? AND s.distance=?"
        val arguments=(baseArguments.asList()+listOf<Any?>(relation.fingerprintVersion,relation.leftRevision,relation.rightRevision,relation.leftAccessEpoch,relation.rightAccessEpoch,relation.distance)).toTypedArray()
        return database.openHelper.readableDatabase.query(visual,arguments).use{it.moveToFirst()&&it.getInt(0)==1}
    }
}
