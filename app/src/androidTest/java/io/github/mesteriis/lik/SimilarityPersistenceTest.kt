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
    @Test fun roomExposureTransitionTableOnlyCrossesCurrentSafeBoundary(){
        val states=listOf<AiExposure?>(null,AiExposure.QUARANTINED,AiExposure.SENSITIVE,AiExposure.SAFE)
        states.forEachIndexed{oldIndex,old->states.forEachIndexed{newIndex,new->triggerFixture{db->
            val media=row("exposure-$oldIndex-$newIndex",MediaSource.DEVICE);db.media().upsert(media)
            old?.let{db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,it,1))}
            val before=db.similarity().libraryRevision()
            when{
                new==null&&old!=null->db.openHelper.writableDatabase.execSQL("DELETE FROM ai_media_exposure WHERE mediaId=? AND contentRevision=?",arrayOf<Any?>(media.mediaId,media.contentRevision))
                new!=null->db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,new,2))
            }
            val expected=before+if((old==AiExposure.SAFE)!=(new==AiExposure.SAFE))1 else 0
            assertEquals("$old -> $new",expected,db.similarity().libraryRevision())
        }}}
    }

    @Test fun mediaAndExposureInsertionDeletionOrdersEachChangeMembershipOnce(){
        listOf(true,false).forEach{exposureFirst->triggerFixture{db->
            val media=row("insert-$exposureFirst",MediaSource.DEVICE);val before=db.similarity().libraryRevision()
            if(exposureFirst){db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,AiExposure.SAFE,1));db.media().upsert(media)}
            else{db.media().upsert(media);db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,AiExposure.SAFE,1))}
            assertEquals(before+1,db.similarity().libraryRevision())
        }}
        listOf(true,false).forEach{exposureFirst->triggerFixture{db->
            val media=row("delete-$exposureFirst",MediaSource.DEVICE);safe(db,media);val before=db.similarity().libraryRevision()
            if(exposureFirst){db.openHelper.writableDatabase.execSQL("DELETE FROM ai_media_exposure WHERE mediaId=?",arrayOf<Any?>(media.mediaId));db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf<Any?>(media.mediaId))}
            else{db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE mediaId=?",arrayOf<Any?>(media.mediaId));db.openHelper.writableDatabase.execSQL("DELETE FROM ai_media_exposure WHERE mediaId=?",arrayOf<Any?>(media.mediaId))}
            assertEquals(before+1,db.similarity().libraryRevision())
        }}
    }

    @Test fun relevantMediaChangesOutsideSafeMembershipDoNotInvalidate(){
        listOf(AiExposure.QUARANTINED,AiExposure.SENSITIVE).forEach{exposure->triggerFixture{db->
            val media=row("hidden-$exposure",MediaSource.DEVICE);db.media().upsert(media);db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,exposure,1));val before=db.similarity().libraryRevision()
            db.media().upsert(media.copy(accessGrantEpoch=9,contentUri="content://changed",sourceKey="changed"))
            assertEquals(before,db.similarity().libraryRevision())
        }}
        triggerFixture{db->
            val media=row("inaccessible-safe",MediaSource.DEVICE).copy(availability=MediaAvailability.INACCESSIBLE);db.media().upsert(media);db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,AiExposure.SAFE,1));val before=db.similarity().libraryRevision()
            db.media().upsert(media.copy(accessGrantEpoch=9,contentUri="content://changed",sourceKey="changed"));assertEquals(before,db.similarity().libraryRevision())
        }
    }

    @Test fun repeatedEligibilityChangesNeverResetBudgetCountersOrPausedState()=triggerFixture{db->
        val media=row("budget",MediaSource.DEVICE);safe(db,media)
        val revision=db.similarity().libraryRevision();db.similarity().saveCheckpoint(SimilarityCheckpoint(checkpointMediaId="cursor",completed=3,total=7,status=SimilarityWorkStatus.RUNNING,updatedAt=1,libraryRevision=revision,tranche=11,comparisons=4321,continuations=6))
        repeat(4){index->val exposure=if(index%2==0)AiExposure.SENSITIVE else AiExposure.SAFE;db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,exposure,index.toLong()+2))}
        val invalidated=db.similarity().checkpoint()!!;assertEquals(11,invalidated.tranche);assertEquals(4321,invalidated.comparisons);assertEquals(6,invalidated.continuations);assertEquals(SimilarityWorkStatus.IDLE,invalidated.status)
        db.similarity().saveCheckpoint(invalidated.copy(checkpointMediaId="paused-cursor",completed=2,total=8,status=SimilarityWorkStatus.PAUSED,updatedAt=9,tranche=12,comparisons=8192,continuations=8))
        repeat(4){index->val exposure=if(index%2==0)AiExposure.SENSITIVE else AiExposure.SAFE;db.ocrPeople().saveExposure(AiMediaExposureRecord(media.mediaId,media.contentRevision,exposure,index.toLong()+20))}
        val paused=db.similarity().checkpoint()!!;assertEquals("paused-cursor",paused.checkpointMediaId);assertEquals(2,paused.completed);assertEquals(8,paused.total);assertEquals(12,paused.tranche);assertEquals(8192,paused.comparisons);assertEquals(8,paused.continuations);assertEquals(SimilarityWorkStatus.PAUSED,paused.status)
        assertEquals(0,db.similarity().failCheckpointUnlessPaused("error",0,0,99,"error",db.similarity().libraryRevision()));assertEquals(paused,db.similarity().checkpoint())
    }

    @Test fun nonmanualRevisionRepreparePreservesBudgetAndManualResumeAloneResetsIt()=fixture{db->
        db.similarity().ensureLibraryState();db.similarity().saveCheckpoint(SimilarityCheckpoint(checkpointMediaId="cursor",completed=1,total=3,status=SimilarityWorkStatus.RUNNING,updatedAt=1,libraryRevision=db.similarity().libraryRevision(),tranche=5,comparisons=321,continuations=4))
        db.similarity().advanceLibraryRevision();val automatic=db.similarity().prepareTranche(false)
        assertEquals(5,automatic.tranche);assertEquals(321,automatic.comparisons);assertEquals(4,automatic.continuations);assertNull(automatic.checkpointMediaId)
        val manual=db.similarity().prepareTranche(true);assertEquals(6,manual.tranche);assertEquals(0,manual.comparisons);assertEquals(0,manual.continuations);assertEquals(SimilarityWorkStatus.RUNNING,manual.status)
    }

    @Test fun routineScanBookkeepingAndNoOpUpsertPreserveReadyVisualGeneration()=triggerFixture{db->
        val a=row("routine-a",MediaSource.DEVICE);val b=row("routine-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        db.similarity().publishIfCurrent(fingerprint(a,"a",ByteArray(8)));db.similarity().publishIfCurrent(fingerprint(b,"b",ByteArray(8).also{it[0]=1}));scanEveryPending(db)
        val revision=db.similarity().libraryRevision();val relation=db.similarity().visibleRelations(10,0).single();val before=db.similarity().fingerprint(a.mediaId)!!
        repeat(100){index->db.media().markScanSeen(a.mediaId,"routine-$index",index.toLong()+10)}
        db.media().upsert(db.media().get(a.mediaId)!!.copy(lastSeenAt=999,displayName="renamed metadata",takenAt=123,modifiedAt=456))
        db.ocrPeople().saveExposure(AiMediaExposureRecord(a.mediaId,a.contentRevision,AiExposure.SAFE,999))
        assertEquals(revision,db.similarity().libraryRevision());assertEquals(before.relationsRevision,db.similarity().fingerprint(a.mediaId)!!.relationsRevision);assertTrue(db.similarity().fingerprint(a.mediaId)!!.relationsReady);assertEquals(relation,db.similarity().visibleRelations(10,0).single())
    }

    @Test fun eachRealDomainChangeBumpsExactlyOnceAndInvalidatesVisualRows(){
        val changes=listOf<(MediaDatabase,MediaRecord)->Unit>(
            {db,row->db.media().upsert(row.copy(contentRevision=row.contentRevision+1))},
            {db,row->db.media().upsert(row.copy(accessGrantEpoch=row.accessGrantEpoch+1))},
            {db,row->db.media().upsert(row.copy(availability=MediaAvailability.INACCESSIBLE))},
            {db,row->db.media().trash(setOf(row.mediaId),44)},
            {db,row->db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,row.contentRevision,AiExposure.SENSITIVE,44))},
        )
        changes.forEachIndexed{index,change->triggerFixture{db->
            val a=row("change-$index-a",MediaSource.GOOGLE_IMPORT,privateId=(index+1).toString().repeat(64).take(64));val b=row("change-$index-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
            db.similarity().publishIfCurrent(fingerprint(a,"a",ByteArray(8)));db.similarity().publishIfCurrent(fingerprint(b,"b",ByteArray(8).also{it[0]=1}));scanEveryPending(db);assertEquals(1,db.similarity().visibleRelations(10,0).size)
            val revision=db.similarity().libraryRevision();change(db,a);assertEquals(revision+1,db.similarity().libraryRevision());assertTrue(db.similarity().visibleRelations(10,0).isEmpty());assertFalse(db.similarity().progress().complete)
        }}
    }

    @Test fun durablePauseBlocksEveryOrdinaryRunUntilExplicitManualResume()=fixture{db->
        val a=row("pause-a",MediaSource.DEVICE);val b=row("pause-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        db.similarity().publishIfCurrent(fingerprint(a,"a",ByteArray(8)));db.similarity().publishIfCurrent(fingerprint(b,"b",ByteArray(8).also{it[0]=1}))
        db.similarity().pauseNow();val before=db.similarity().checkpoint()!!
        repeat(25){assertEquals(SimilarityRunOutcome.PAUSED_BUDGET,SimilarityProcessor(db,FingerprintCalculator{_,_->throw AssertionError("paused")},{false}).run())}
        val still=db.similarity().checkpoint()!!;assertEquals(before.comparisons,still.comparisons);assertEquals(before.continuations,still.continuations);assertEquals(SimilarityWorkStatus.PAUSED,still.status)
        SimilarityProcessor(db,FingerprintCalculator{_,_->throw AssertionError("fingerprinted")},{false},newTranche=true).run();assertTrue(db.similarity().checkpoint()!!.status!=SimilarityWorkStatus.PAUSED)
    }

    @Test fun pauseRaceAfterFingerprintStopsBeforeComparisonReservation()=fixture{db->
        val a=row("race-a",MediaSource.DEVICE);val b=row("race-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        db.similarity().publishIfCurrent(fingerprint(b,"b",ByteArray(8).also{it[0]=1}))
        val outcome=SimilarityProcessor(db,FingerprintCalculator{row,_->db.similarity().pauseNow();CalculatedFingerprint(row.mediaId,ByteArray(8))},{false}).run()
        assertEquals(SimilarityRunOutcome.PAUSED_BUDGET,outcome);assertEquals(0,db.similarity().checkpoint()!!.comparisons);assertEquals(0,db.similarity().relationCount());assertEquals(SimilarityWorkStatus.PAUSED,db.similarity().checkpoint()!!.status)
    }

    @Test fun durablePauseIsCheckedBeforeEveryCandidatePage()=fixture{db->
        val owner=row("page-000",MediaSource.DEVICE);safe(db,owner);db.similarity().publishIfCurrent(fingerprint(owner,"owner",ByteArray(8)))
        repeat(140){index->val peer=row("page-${(index+100).toString().padStart(3,'0')}",MediaSource.DEVICE);safe(db,peer);val value=(index+1).toLong() shl 4;val bits=ByteArray(8){offset->(value ushr(offset*8)).toByte()};db.similarity().publishIfCurrent(fingerprint(peer,"peer-$index",bits))}
        var checks=0;var interrupted=false
        try{SimilarityRelationScanner.step(db,owner.mediaId,SimilarityBudgets.CANDIDATES_PER_ITEM_STEP){checks++;if(checks==2){db.similarity().pauseNow();true}else false}}catch(_:InterruptedException){interrupted=true}
        assertTrue(interrupted);assertEquals(2,checks);assertEquals(SimilarityWorkStatus.PAUSED,db.similarity().checkpoint()!!.status);assertEquals(SimilarityBudgets.CANDIDATE_PAGE,db.similarity().scan(owner.mediaId)!!.examined);assertTrue(db.similarity().relationCount()<=SimilarityBudgets.TOP_K)
    }

    @Test fun pausedCheckpointSurvivesDatabaseReopenAndStillHardStops(){
        val context=ApplicationProvider.getApplicationContext<Context>();val name="similarity-pause-${System.nanoTime()}.db"
        fun open()=Room.databaseBuilder(context,MediaDatabase::class.java,name).build()
        try{
            val first=open();try{first.similarity().ensureLibraryState();first.similarity().saveCheckpoint(SimilarityCheckpoint(checkpointMediaId="kept",completed=3,total=9,status=SimilarityWorkStatus.RUNNING,updatedAt=1,libraryRevision=first.similarity().libraryRevision(),comparisons=41,continuations=2));first.similarity().pauseNow()}finally{first.close()}
            val reopened=open();try{assertEquals(SimilarityRunOutcome.PAUSED_BUDGET,SimilarityProcessor(reopened,FingerprintCalculator{_,_->throw AssertionError("paused")},{false}).run());val kept=reopened.similarity().checkpoint()!!;assertEquals(41,kept.comparisons);assertEquals(2,kept.continuations);assertEquals("kept",kept.checkpointMediaId)}finally{reopened.close()}
        }finally{context.deleteDatabase(name)}
    }

    @Test fun libraryDomainChangeHidesAndRecomputesOwnerTopEightWithoutDeletingExactGroups()=fixture{db->
        val owner=row("domain-a",MediaSource.DEVICE);safe(db,owner);db.similarity().publishIfCurrent(fingerprint(owner,"owner",ByteArray(8)))
        ('b'..'j').forEachIndexed{index,char->val peer=row("domain-$char",MediaSource.DEVICE);safe(db,peer);db.similarity().publishIfCurrent(fingerprint(peer,"peer-$char",ByteArray(8).also{it[index/8]=(1 shl(index%8)).toByte()}))}
        val exact1=row("exact-domain-1",MediaSource.DEVICE);val exact2=row("exact-domain-2",MediaSource.DEVICE);safe(db,exact1);safe(db,exact2);db.similarity().publishIfCurrent(fingerprint(exact1,"exact-kept",ByteArray(8){0x55}));db.similarity().publishIfCurrent(fingerprint(exact2,"exact-kept",ByteArray(8){0x55}))
        scanEveryPending(db);assertEquals(('b'..'i').map{"domain-$it"},db.similarity().ownerRelationIds(owner.mediaId));val oldSafeRelation=db.similarity().visibleRelations(100,0).first{it.leftMediaId==owner.mediaId&&it.rightMediaId=="domain-c"};assertEquals(1,db.similarity().exactGroups(10,0).size)
        db.ocrPeople().saveExposure(AiMediaExposureRecord("domain-b",0,AiExposure.SENSITIVE,2));db.similarity().advanceLibraryRevision()
        assertTrue(db.similarity().visibleRelations(100,0).isEmpty());assertFalse(SimilarityPublicationGuard.visible(db,oldSafeRelation));assertFalse(db.similarity().progress().complete);assertEquals(1,db.similarity().exactGroups(10,0).size)
        scanEveryPending(db);assertEquals(('c'..'j').map{"domain-$it"},db.similarity().ownerRelationIds(owner.mediaId));assertTrue(db.similarity().progress().complete)
    }

    @Test fun partialCursorRestartsWhenLibraryRevisionChanges()=fixture{db->
        val owner=row("cursor-000",MediaSource.DEVICE);safe(db,owner);db.similarity().publishIfCurrent(fingerprint(owner,"owner",ByteArray(8)))
        repeat(40){index->val peer=row("cursor-${(index+100).toString().padStart(3,'0')}",MediaSource.DEVICE);safe(db,peer);db.similarity().publishIfCurrent(fingerprint(peer,"peer-$index",ByteArray(8).also{it[index/8]=(1 shl(index%8)).toByte()}))}
        db.similarity().prepareTranche(false);assertFalse(SimilarityRelationScanner.step(db,owner.mediaId,17).complete);val old=db.similarity().scan(owner.mediaId)!!;assertEquals(17,old.examined)
        db.similarity().advanceLibraryRevision();assertFalse(SimilarityRelationScanner.step(db,owner.mediaId,17).complete);val restarted=db.similarity().scan(owner.mediaId)!!;assertTrue(restarted.libraryRevision>old.libraryRevision);assertEquals(17,restarted.examined)
    }
    private fun fixture(block:(MediaDatabase)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
    private fun triggerFixture(block:(MediaDatabase)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MediaDatabase::class.java).addCallback(MediaDatabase.SIMILARITY_CALLBACK).build()
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
        listOf(fingerprint(a,"a",left),fingerprint(b,"b",near),fingerprint(c,"c",far)).forEach{assertTrue(db.similarity().publishIfCurrent(it))};scanEveryPending(db)
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
        repeat(40){index->val candidate=row("candidate-${index.toString().padStart(3,'0')}",MediaSource.DEVICE);safe(db,candidate);val bits=base.copyOf().also{it[index/8]=(1 shl (index%8)).toByte()};assertTrue(db.similarity().publishIfCurrent(fingerprint(candidate,"sha-$index",bits)))}
        val first=SimilarityRelationScanner.step(db,current.mediaId,17)
        assertEquals(17,first.examined);assertFalse(first.complete);assertNotNull(db.similarity().scan(current.mediaId));assertTrue(db.similarity().relationCount()<=SimilarityBudgets.TOP_K)
        while(!SimilarityRelationScanner.step(db,current.mediaId,17).complete){}
        assertNull(db.similarity().scan(current.mediaId));assertTrue(db.similarity().relationCount()<=SimilarityBudgets.TOP_K)
        scanEveryPending(db)
        assertEquals(SimilarityBudgets.TOP_K,db.similarity().visibleRelations(1_000,0).count{it.leftMediaId==current.mediaId})
    }

    @Test fun exhaustedLibraryTranchePausesWithoutClaimingCompleteAndManualTrancheResumes()=fixture{db->
        val a=row("budget-a",MediaSource.DEVICE);val b=row("budget-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        db.similarity().publishIfCurrent(fingerprint(a,"a",ByteArray(8){0xff.toByte()}));db.similarity().publishIfCurrent(fingerprint(b,"b",ByteArray(8){0xff.toByte()}.also{it[0]=0xfe.toByte()}))
        db.similarity().saveCheckpoint(SimilarityCheckpoint(checkpointMediaId=null,completed=0,total=2,status=SimilarityWorkStatus.RUNNING,updatedAt=1,libraryRevision=db.similarity().libraryRevision(),comparisons=SimilarityBudgets.COMPARISONS_PER_TRANCHE-1))
        val engine=FingerprintCalculator{_,_->throw AssertionError("fingerprints already exist")}
        assertEquals(SimilarityRunOutcome.PAUSED_BUDGET,SimilarityProcessor(db,engine,{false}).run())
        assertEquals(SimilarityBudgets.COMPARISONS_PER_TRANCHE,db.similarity().checkpoint()!!.comparisons);assertEquals(SimilarityWorkStatus.PAUSED,db.similarity().checkpoint()!!.status);assertFalse(db.similarity().progress().complete)
        db.similarity().saveCheckpoint(db.similarity().checkpoint()!!.copy(status=SimilarityWorkStatus.RUNNING,continuations=SimilarityBudgets.MAX_AUTO_CONTINUATIONS));assertEquals(0,db.similarity().claimContinuation())
        val resumed=SimilarityProcessor(db,engine,{false},newTranche=true).run();assertTrue(resumed==SimilarityRunOutcome.COMPLETE||resumed==SimilarityRunOutcome.MORE_WORK);assertTrue(db.similarity().checkpoint()!!.tranche>0)
    }

    @Test fun commonPerceptualHashUsesOneKnownDistanceBucketAndBoundedRelations()=fixture{db->
        val current=row("common-000",MediaSource.DEVICE);safe(db,current);db.similarity().publishIfCurrent(fingerprint(current,"sha-current",ByteArray(8)))
        repeat(100){index->val row=row("common-${(index+1).toString().padStart(3,'0')}",MediaSource.DEVICE);safe(db,row);db.similarity().publishIfCurrent(fingerprint(row,"sha-$index",ByteArray(8)))}
        val result=SimilarityRelationScanner.step(db,current.mediaId,2);assertTrue(result.complete);assertEquals(1,result.examined);assertEquals(0,result.comparisons);assertTrue(db.similarity().relationCount()<=SimilarityBudgets.TOP_K)
    }

    @Test fun visibleVisualRelationsRequireBothCurrentVersionFingerprints()=fixture{db->
        val a=row("version-a",MediaSource.DEVICE);val b=row("version-b",MediaSource.DEVICE);safe(db,a);safe(db,b)
        db.similarity().publishIfCurrent(fingerprint(a,"a",ByteArray(8)));db.similarity().publishIfCurrent(fingerprint(b,"b",ByteArray(8)))
        scanEveryPending(db);val relation=db.similarity().visibleRelations(10,0).single()
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
    private fun scanEveryPending(db:MediaDatabase){db.similarity().prepareTranche(false);while(true){val row=db.similarity().pendingSafe(null,1).firstOrNull()?:return;while(!SimilarityRelationScanner.step(db,row.mediaId,512).complete){}}}
}
