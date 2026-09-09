package io.github.mesteriis.lik.ai

import io.github.mesteriis.lik.catalog.MediaDatabase
import java.util.UUID

data class PersonGroup(val personId:String,val name:String?,val faces:List<VisibleFaceRow>)

class PeopleRepository(private val database:MediaDatabase){
    private val dao get()=database.ocrPeople()
    fun groups(generationId:String,beforeFinalCheck:()->Unit={}):List<PersonGroup> = GenerationUseCoordinator.read(generationId) {
        val people=dao.people().associateBy{it.personId};val decisions=dao.decisions();val manual=ManualPeopleState(
            assignments=decisions.filter{it.decision==ManualFaceDecision.ASSIGN&&it.personId!=null}.associate{it.anchorId to requireNotNull(it.personId)},
            excluded=decisions.filter{it.decision==ManualFaceDecision.EXCLUDE}.map{it.anchorId}.toSet(),
            merges=dao.merges().associate{it.fromPersonId to it.intoPersonId},
            cannotLinks=dao.cannotLinks().map{ManualFacePair.ordered(it.leftAnchorId,it.rightAnchorId)}.toSet())
        dao.visibleFaces(generationId);beforeFinalCheck()
        val visible=dao.visibleFaces(generationId);val resolved=PeopleResolution.apply(visible.map{ComputedFace(it.detectionId,it.anchorId,it.computedClusterId)},manual)
        val byId=visible.associateBy{it.detectionId}
        resolved.filterNot{it.excluded}.groupBy{it.personId}.map{(id,rows)->PersonGroup(id,people[id]?.name,rows.mapNotNull{byId[it.detectionId]}.sortedWith(compareBy<VisibleFaceRow>{it.mediaId}.thenBy{it.anchorId}))}.sortedWith(compareBy<PersonGroup>{it.name?:"\uffff"}.thenBy{it.personId})
    }
    fun excluded(generationId:String,beforeFinalCheck:()->Unit={}):List<VisibleFaceRow> = GenerationUseCoordinator.read(generationId) { dao.visibleFaces(generationId);beforeFinalCheck();dao.visibleFaces(generationId)
        .filter { it.manualDecision == ManualFaceDecision.EXCLUDE }
        .sortedWith(compareBy<VisibleFaceRow>{it.mediaId}.thenBy{it.anchorId}) }
    fun mergedSources(personId:String):List<PersonIdentityRecord>{
        val merges=dao.merges().associate{it.fromPersonId to it.intoPersonId}
        val state=ManualPeopleState(merges=merges);val target=state.canonical(personId)
        val people=dao.people().associateBy{it.personId}
        return merges.keys.filter{state.canonical(it)==target}.mapNotNull(people::get)
            .sortedWith(compareBy<PersonIdentityRecord>{it.name?:"\uffff"}.thenBy{it.personId})
    }
    fun create(name:String?=null):String=UUID.randomUUID().toString().also{dao.savePerson(PersonIdentityRecord(it,name?.trim()?.takeIf(String::isNotEmpty),System.currentTimeMillis()))}
    fun name(personId:String,name:String){require(name.isNotBlank());ensure(personId);dao.namePerson(personId,name.trim())}
    fun name(generationId:String,personId:String,name:String):String=materialize(generationId,personId).also{name(it,name)}
    fun move(anchorId:String,personId:String){ensure(personId);dao.saveDecision(PersonFaceDecisionRecord(anchorId,ManualFaceDecision.ASSIGN,personId,System.currentTimeMillis()))}
    fun move(generationId:String,anchorId:String,personId:String)=move(anchorId,materialize(generationId,personId))
    fun exclude(anchorId:String)=dao.saveDecision(PersonFaceDecisionRecord(anchorId,ManualFaceDecision.EXCLUDE,null,System.currentTimeMillis()))
    fun clear(anchorId:String)=dao.clearDecision(anchorId)
    fun merge(from:String,into:String){require(from!=into);ensure(from);ensure(into);require(!wouldCycle(from,into));dao.saveMerge(PersonMergeRecord(from,into,System.currentTimeMillis()))}
    fun merge(generationId:String,from:String,into:String){merge(materialize(generationId,from),materialize(generationId,into))}
    fun unmerge(from:String)=dao.removeMerge(from)
    fun split(generationId:String,anchors:Set<String>,fromPerson:String,name:String?=null):String{require(anchors.isNotEmpty());val stableFrom=materialize(generationId,fromPerson);val others=groups(generationId).firstOrNull{it.personId==stableFrom}?.faces.orEmpty().map{it.anchorId}.filterNot(anchors::contains).toSet();return splitWithOthers(anchors,others,stableFrom,name)}
    @Deprecated("Pass generationId so computed members participate in the durable split")
    fun split(anchors:Set<String>,fromPerson:String,name:String?=null):String=splitWithOthers(anchors,groupsForAll().filter{it.value==fromPerson&&it.key !in anchors}.keys,fromPerson,name)
    private fun splitWithOthers(anchors:Set<String>,others:Set<String>,fromPerson:String,name:String?):String{require(anchors.isNotEmpty());val target=create(name);database.runInTransaction{dao.saveSplit(PersonSplitRecord(target,fromPerson,System.currentTimeMillis()));anchors.forEach{move(it,target)};anchors.forEach{a->others.forEach{b->contributeCannotLink(a,b,"split:$target")}}};return target}
    fun splitSource(personId:String):PersonSplitRecord?=dao.split(personId)
    fun undoSplit(personId:String):Int{val split=dao.split(personId)?:return 0;var restored=0;database.runInTransaction{dao.decisionsForPerson(personId).forEach{decision->dao.saveDecision(decision.copy(personId=split.fromPersonId,updatedAt=System.currentTimeMillis()));restored++};removeContribution("split:$personId");dao.removeSplit(personId)};return restored}
    fun manualCannotLink(left:String,right:String)=contributeCannotLink(left,right,"manual")
    private fun contributeCannotLink(left:String,right:String,owner:String){val pair=ManualFacePair.ordered(left,right);val now=System.currentTimeMillis();dao.saveCannotLink(PersonCannotLinkRecord(pair.first,pair.second,now,null));dao.saveCannotLinkOwner(PersonCannotLinkOwnerRecord(pair.first,pair.second,owner,now))}
    private fun removeContribution(owner:String){val pairs=dao.cannotLinkOwners(owner);dao.removeCannotLinkOwner(owner);pairs.forEach{if(dao.cannotLinkOwnerCount(it.leftAnchorId,it.rightAnchorId)==0)dao.removeCannotLink(it.leftAnchorId,it.rightAnchorId)}}
    private fun materialize(generationId:String,personId:String):String{
        if(dao.person(personId)!=null)return personId
        val group=groups(generationId).firstOrNull{it.personId==personId}?:return personId.also(::ensure)
        val stable=create(group.name)
        database.runInTransaction{group.faces.forEach{move(it.anchorId,stable)}}
        return stable
    }
    private fun groupsForAll()=dao.decisions().filter{it.personId!=null}.associate{it.anchorId to requireNotNull(it.personId)}
    private fun ensure(id:String){if(dao.person(id)==null)dao.savePerson(PersonIdentityRecord(id,null,System.currentTimeMillis()))}
    private fun wouldCycle(from:String,into:String):Boolean{var id=into;val merges=dao.merges().associate{it.fromPersonId to it.intoPersonId};repeat(merges.size+1){if(id==from)return true;id=merges[id]?:return false};return true}
}
