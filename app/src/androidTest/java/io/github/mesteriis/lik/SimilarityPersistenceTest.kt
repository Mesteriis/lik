package io.github.mesteriis.lik

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mesteriis.lik.ai.AiExposure
import io.github.mesteriis.lik.ai.AiMediaExposureRecord
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.similarity.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import android.util.Base64

class SimilarityPersistenceTest {
    private fun fixture(block:(MediaDatabase)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    @Test fun exactBytesAcrossDeviceAndImportProduceRelationWithoutChangingIdentity()=fixture{db->
        val device=row("device",MediaSource.DEVICE,revision=8,epoch=2)
        val imported=row("import:"+"a".repeat(64),MediaSource.GOOGLE_IMPORT,privateId="a".repeat(64))
        safe(db,device);safe(db,imported)
        val first=fingerprint(device,"same",byteArrayOf(1,2,3)).copy(perceptualVersion=0);val second=fingerprint(imported,"same",byteArrayOf(9,8,7))
        assertTrue(db.similarity().publishIfCurrent(first))
        assertTrue(db.similarity().publishIfCurrent(second))
        val group=db.similarity().exactGroups(20,0).single()
        assertEquals("same",group.sha256);assertEquals(2,group.memberCount)
        assertEquals(listOf(device.mediaId,imported.mediaId).sorted(),db.similarity().exactMembers("same","",20).map{it.mediaId})
        assertEquals(0,db.similarity().relationCount())
        assertEquals(device.mediaId,db.media().get(device.mediaId)!!.mediaId)
    }

    @Test fun exactDigestUsesPagedGroupsAndMembersWithoutQuadraticPairRows()=fixture{db->
        repeat(257){index->val media=row("exact-${index.toString().padStart(3,'0')}",MediaSource.DEVICE);safe(db,media);db.similarity().saveFingerprint(fingerprint(media,"one-large-group",ByteArray(8)).copy(relationsReady=true))}
        assertEquals(257,db.similarity().exactGroups(2,0).single().memberCount)
        val first=db.similarity().exactMembers("one-large-group","",61);assertEquals(61,first.size)
        val second=db.similarity().exactMembers("one-large-group",first.last().mediaId,61);assertEquals(61,second.size);assertTrue(second.first().mediaId>first.last().mediaId)
        assertEquals(0,db.similarity().relationCount())
    }

    @Test fun reusedMediaStoreRowAndAccessEpochRejectOldPublicationAndOldRelation()=fixture{db->
        val old=row("device-row",MediaSource.DEVICE,revision=10,epoch=1);safe(db,old)
        val peer=row("peer",MediaSource.DEVICE);safe(db,peer)
        db.similarity().publishIfCurrent(fingerprint(old,"same",ByteArray(8)))
        db.similarity().publishIfCurrent(fingerprint(peer,"same",ByteArray(8)))
        assertEquals(1,db.similarity().exactGroups(20,0).size)
        val reused=old.copy(contentRevision=1,accessGrantEpoch=2,displayName="new.jpg")
        db.media().upsert(reused);safe(db,reused)
        assertTrue(db.similarity().exactGroups(20,0).isEmpty())
        assertFalse(db.similarity().publishIfCurrent(fingerprint(old,"stale",ByteArray(8))))
        assertTrue(db.similarity().publishIfCurrent(fingerprint(reused,"new",ByteArray(8){1})))
    }

    @Test fun inaccessibleTrashSensitiveAndUnclassifiedNeverAppearInRelations()=fixture{db->
        val a=row("a",MediaSource.DEVICE);val b=row("b",MediaSource.DEVICE)
        safe(db,a);safe(db,b);db.similarity().publishIfCurrent(fingerprint(a,"same",ByteArray(8)));db.similarity().publishIfCurrent(fingerprint(b,"same",ByteArray(8)))
        assertEquals(1,db.similarity().exactGroups(20,0).size)
        db.ocrPeople().saveExposure(AiMediaExposureRecord("b",0,AiExposure.SENSITIVE,2));assertTrue(db.similarity().exactGroups(20,0).isEmpty())
        db.ocrPeople().saveExposure(AiMediaExposureRecord("b",0,AiExposure.SAFE,3));db.media().upsert(b.copy(availability=MediaAvailability.INACCESSIBLE));assertTrue(db.similarity().exactGroups(20,0).isEmpty())
        db.media().upsert(b.copy(availability=MediaAvailability.TRASHED,trashedAt=4));assertTrue(db.similarity().exactGroups(20,0).isEmpty())
        db.media().upsert(b);db.openHelper.writableDatabase.execSQL("DELETE FROM ai_media_exposure WHERE mediaId='b'");assertTrue(db.similarity().exactGroups(20,0).isEmpty())
    }

    @Test fun uiPublicationGuardRejectsExposureRaceAfterRepositoryReturn()=fixture{db->
        val a=row("guard-a",MediaSource.DEVICE);val b=row("guard-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        listOf(a,b).forEach{db.similarity().publishIfCurrent(fingerprint(it,"same",ByteArray(8)))}
        val returned=SimilarityRepository(ApplicationProvider.getApplicationContext(),db).pair(a.mediaId,b.mediaId)!!;assertTrue(SimilarityPublicationGuard.visible(db,returned.relation))
        db.ocrPeople().saveExposure(AiMediaExposureRecord(b.mediaId,b.contentRevision,AiExposure.SENSITIVE,2))
        assertFalse(SimilarityPublicationGuard.visible(db,returned.relation))
    }

    @Test fun similarAndDissimilarPairsUseVersionAndThreshold()=fixture{db->
        val a=row("a",MediaSource.DEVICE);val b=row("b",MediaSource.DEVICE);val c=row("c",MediaSource.DEVICE)
        listOf(a,b,c).forEach{safe(db,it)}
        val left=ByteArray(8);val near=left.copyOf().also{it[0]=0x3f};val far=ByteArray(8){0xff.toByte()}
        listOf(fingerprint(a,"a",left),fingerprint(b,"b",near),fingerprint(c,"c",far)).forEach{assertTrue(db.similarity().publishIfCurrent(it));while(!SimilarityRelationScanner.step(db,it.mediaId,100).complete){}}
        val relations=db.similarity().visibleRelations(20,0)
        assertEquals(listOf(SimilarityKind.VISUAL),relations.map{it.kind})
        assertEquals(6,relations.single().distance)
        assertEquals(PerceptualFingerprintV2.VERSION,relations.single().fingerprintVersion)
    }

    @Test fun checkpointAndPersistedRowsMakeProcessingResumable()=fixture{db->
        val a=row("a",MediaSource.DEVICE);val b=row("b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        assertEquals(listOf("a","b"),db.similarity().pendingSafe(null,10).map{it.mediaId})
        val done=fingerprint(a,"a",ByteArray(8));db.similarity().publishIfCurrent(done);while(!SimilarityRelationScanner.step(db,a.mediaId,100).complete){}
        db.similarity().saveCheckpoint(SimilarityCheckpoint("default","a",1,2,SimilarityWorkStatus.RUNNING,1))
        assertEquals(listOf("b"),db.similarity().pendingSafe(null,10).map{it.mediaId})
        assertEquals("a",db.similarity().checkpoint()!!.checkpointMediaId)
    }

    @Test fun corruptFailureIsRevisionScopedAndDoesNotLoopUntilContentChanges()=fixture{db->
        val corrupt=row("corrupt",MediaSource.DEVICE,revision=3);safe(db,corrupt)
        assertTrue(db.similarity().failIfCurrent(FingerprintFailureRecord("corrupt",3,1,PerceptualFingerprintV2.VERSION,"CORRUPT",1)))
        assertTrue(db.similarity().pendingSafe(null,10).isEmpty())
        val repaired=corrupt.copy(contentRevision=4);safe(db,repaired)
        assertEquals(listOf("corrupt"),db.similarity().pendingSafe(null,10).map{it.mediaId})
    }

    @Test fun processorRecordsClippedImageFailureAndFinishesOtherRows()=fixture{db->
        val clipped=row("clipped",MediaSource.DEVICE);val good=row("good",MediaSource.DEVICE);safe(db,clipped);safe(db,good)
        val calculator=FingerprintCalculator{media,_->if(media.mediaId=="clipped")throw IOException("partial image")else CalculatedFingerprint("good",ByteArray(8))}
        SimilarityProcessor(db,calculator,{false}).run()
        assertEquals("IOException",db.similarity().failure("clipped",0,1,PerceptualFingerprintV2.VERSION)!!.error)
        assertTrue(db.similarity().fingerprint("good")!!.relationsReady)
        assertEquals(SimilarityWorkStatus.ERROR,db.similarity().checkpoint()!!.status)
    }

    @Test fun adversarialNearHashIsFoundAndVisualScanPersistsBoundedContinuation()=fixture{db->
        val current=row("000-current",MediaSource.DEVICE);safe(db,current);val base=ByteArray(8);assertTrue(db.similarity().publishIfCurrent(fingerprint(current,"current",base)))
        repeat(40){index->val candidate=row("candidate-${index.toString().padStart(3,'0')}",MediaSource.DEVICE);safe(db,candidate);val bits=base.copyOf().also{for(byte in it.indices)it[byte]=(1 shl (byte%8)).toByte()};assertTrue(db.similarity().publishIfCurrent(fingerprint(candidate,"sha-$index",bits)))}
        val first=SimilarityRelationScanner.step(db,current.mediaId,17)
        assertEquals(17,first.examined);assertFalse(first.complete);assertNotNull(db.similarity().scan(current.mediaId));assertTrue(db.similarity().relationCount()<=SimilarityBudgets.TOP_K)
        while(!SimilarityRelationScanner.step(db,current.mediaId,17).complete){}
        assertNull(db.similarity().scan(current.mediaId));assertTrue(db.similarity().relationCount()<=SimilarityBudgets.TOP_K)
        assertEquals(SimilarityBudgets.TOP_K,db.similarity().visibleRelations(100,0).size)
    }

    @Test fun visibleVisualRelationsRequireBothCurrentVersionFingerprints()=fixture{db->
        val a=row("version-a",MediaSource.DEVICE);val b=row("version-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        db.similarity().publishIfCurrent(fingerprint(a,"a",ByteArray(8)));db.similarity().publishIfCurrent(fingerprint(b,"b",ByteArray(8)))
        val relation=SimilarityRelationRecord(a.mediaId,b.mediaId,a.contentRevision,b.contentRevision,a.accessGrantEpoch,b.accessGrantEpoch,SimilarityKind.VISUAL,PerceptualFingerprintV2.VERSION,1,1)
        db.similarity().saveRelation(relation)
        assertEquals(1,db.similarity().visibleRelations(10,0).size)
        val oldRelation=relation.copy(fingerprintVersion=PerceptualFingerprintV2.VERSION-1)
        db.similarity().saveRelation(oldRelation)
        assertFalse(SimilarityPublicationGuard.visible(db,oldRelation))
        assertTrue(db.similarity().visibleRelations(10,0).isEmpty())
        db.similarity().saveRelation(relation)
        db.similarity().saveFingerprint(fingerprint(b,"b",ByteArray(8)).copy(perceptualVersion=PerceptualFingerprintV2.VERSION-1))
        assertTrue(db.similarity().visibleRelations(10,0).isEmpty())
    }

    @Test fun progressUsesOnlyLiveSafeRowsAndCannotCompleteWithFailure()=fixture{db->
        val safe=row("progress-safe",MediaSource.DEVICE);val hidden=row("progress-hidden",MediaSource.DEVICE);safe(db,safe);db.media().upsert(hidden);db.ocrPeople().saveExposure(AiMediaExposureRecord(hidden.mediaId,0,AiExposure.SENSITIVE,1))
        val f=fingerprint(safe,"safe",ByteArray(8));db.similarity().publishIfCurrent(f);while(!SimilarityRelationScanner.step(db,safe.mediaId,10).complete){}
        assertTrue(db.similarity().commitProgress().complete);assertEquals(1,db.similarity().progress().eligible)
        val failed=row("progress-failed",MediaSource.DEVICE);safe(db,failed);db.similarity().failIfCurrent(FingerprintFailureRecord(failed.mediaId,0,1,PerceptualFingerprintV2.VERSION,"CORRUPT",1))
        val progress=db.similarity().commitProgress();assertFalse(progress.complete);assertEquals(2,progress.eligible);assertEquals(1,progress.completed);assertEquals(SimilarityWorkStatus.ERROR,db.similarity().checkpoint()!!.status)
    }

    @Test fun processorRejectsRevokedAndStaleResultsThenIndexesOnlyNewRevision()=fixture{db->
        val revoked=row("revoked",MediaSource.DEVICE);val changing=row("changing",MediaSource.DEVICE,revision=1);safe(db,revoked);safe(db,changing);var changed=false
        val calculator=FingerprintCalculator{media,_->when(media.mediaId){
            "revoked"->{db.media().upsert(media.copy(availability=MediaAvailability.INACCESSIBLE));throw SecurityException("revoked")}
            else->{if(!changed){changed=true;val next=media.copy(contentRevision=2);safe(db,next)};CalculatedFingerprint("revision-${media.contentRevision}",ByteArray(8))}
        }}
        SimilarityProcessor(db,calculator,{false}).run()
        assertNull(db.similarity().fingerprint("revoked"));assertNull(db.similarity().failure("revoked",0,1,PerceptualFingerprintV2.VERSION))
        assertEquals(2,db.similarity().fingerprint("changing")!!.contentRevision)
        assertEquals("revision-2",db.similarity().fingerprint("changing")!!.sha256)
    }

    @Test fun deletionActionRoutesOnlyImportedCopyThroughThirtyDayTrash()=fixture{db->
        val context=ApplicationProvider.getApplicationContext<Context>();val store=io.github.mesteriis.lik.imports.PhotoLibrary.store(context)
        val stored=io.github.mesteriis.lik.catalog.TrashRepository(db,store).importPhoto(Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",Base64.DEFAULT).inputStream())
        val imported=requireNotNull(db.media().get(stored.photo.id));safe(db,imported)
        val device=row("readonly",MediaSource.DEVICE);safe(db,device)
        val repository=SimilarityRepository(context,db)
        try{assertFalse(repository.moveImportedToTrash(device.mediaId));assertTrue(repository.moveImportedToTrash(imported.mediaId));assertEquals(MediaAvailability.TRASHED,db.media().get(imported.mediaId)!!.availability)}finally{io.github.mesteriis.lik.catalog.TrashRepository(db,store).purgeNow(setOf(imported.mediaId))}
    }

    private fun row(id:String,source:MediaSource,revision:Long=0,epoch:Long=1,privateId:String?=null)=MediaRecord(id,source,id,contentUri=if(source==MediaSource.DEVICE)"content://$id" else null,privateFileId=privateId,contentRevision=revision,accessGrantEpoch=epoch,lastSeenAt=1)
    private fun safe(db:MediaDatabase,row:MediaRecord){db.media().upsert(row);db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,row.contentRevision,AiExposure.SAFE,1))}
    private fun fingerprint(row:MediaRecord,sha:String,bits:ByteArray)=ContentFingerprintRecord(row.mediaId,row.contentRevision,row.accessGrantEpoch,sha,PerceptualFingerprintV2.VERSION,bits,1)
}
