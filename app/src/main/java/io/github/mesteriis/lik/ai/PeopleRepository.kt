package io.github.mesteriis.lik.ai

import io.github.mesteriis.lik.catalog.MediaDatabase
import java.util.UUID

data class PersonGroup(val personId:String,val name:String?,val faces:List<VisibleFaceRow>)

class PeopleRepository(private val database:MediaDatabase){
    private val dao get()=database.ocrPeople()
    fun groups(generationId:String):List<PersonGroup>{
        val people=dao.people().associateBy{it.personId};val decisions=dao.decisions();val manual=ManualPeopleState(
            assignments=decisions.filter{it.decision==ManualFaceDecision.ASSIGN&&it.personId!=null}.associate{it.anchorId to requireNotNull(it.personId)},
            excluded=decisions.filter{it.decision==ManualFaceDecision.EXCLUDE}.map{it.anchorId}.toSet(),
            merges=dao.merges().associate{it.fromPersonId to it.intoPersonId},
            cannotLinks=dao.cannotLinks().map{ManualFacePair.ordered(it.leftAnchorId,it.rightAnchorId)}.toSet())
        val visible=dao.visibleFaces(generationId);val resolved=PeopleResolution.apply(visible.map{ComputedFace(it.detectionId,it.anchorId,it.computedClusterId)},manual)
        val byId=visible.associateBy{it.detectionId}
        return resolved.filterNot{it.excluded}.groupBy{it.personId}.map{(id,rows)->PersonGroup(id,people[id]?.name,rows.mapNotNull{byId[it.detectionId]}.sortedWith(compareBy<VisibleFaceRow>{it.mediaId}.thenBy{it.anchorId}))}.sortedWith(compareBy<PersonGroup>{it.name?:"\uffff"}.thenBy{it.personId})
    }
    fun excluded(generationId:String):List<VisibleFaceRow> = dao.visibleFaces(generationId)
        .filter { it.manualDecision == ManualFaceDecision.EXCLUDE }
        .sortedWith(compareBy<VisibleFaceRow>{it.mediaId}.thenBy{it.anchorId})
    fun mergedSources(personId:String):List<PersonIdentityRecord>{
        val merges=dao.merges().associate{it.fromPersonId to it.intoPersonId}
        val state=ManualPeopleState(merges=merges);val target=state.canonical(personId)
        val people=dao.people().associateBy{it.personId}
        return merges.keys.filter{state.canonical(it)==target}.mapNotNull(people::get)
            .sortedWith(compareBy<PersonIdentityRecord>{it.name?:"\uffff"}.thenBy{it.personId})
    }
    fun create(name:String?=null):String=UUID.randomUUID().toString().also{dao.savePerson(PersonIdentityRecord(it,name?.trim()?.takeIf(String::isNotEmpty),System.currentTimeMillis()))}
    fun name(personId:String,name:String){require(name.isNotBlank());ensure(personId);dao.namePerson(personId,name.trim())}
    fun move(anchorId:String,personId:String){ensure(personId);dao.saveDecision(PersonFaceDecisionRecord(anchorId,ManualFaceDecision.ASSIGN,personId,System.currentTimeMillis()))}
    fun exclude(anchorId:String)=dao.saveDecision(PersonFaceDecisionRecord(anchorId,ManualFaceDecision.EXCLUDE,null,System.currentTimeMillis()))
    fun clear(anchorId:String)=dao.clearDecision(anchorId)
    fun merge(from:String,into:String){require(from!=into);ensure(from);ensure(into);require(!wouldCycle(from,into));dao.saveMerge(PersonMergeRecord(from,into,System.currentTimeMillis()))}
    fun unmerge(from:String)=dao.removeMerge(from)
    fun split(anchors:Set<String>,fromPerson:String,name:String?=null):String{require(anchors.isNotEmpty());val target=create(name);anchors.forEach{move(it,target)};val others=groupsForAll().filter{it.value==fromPerson&&it.key !in anchors}.keys;anchors.forEach{a->others.forEach{b->val pair=ManualFacePair.ordered(a,b);dao.saveCannotLink(PersonCannotLinkRecord(pair.first,pair.second,System.currentTimeMillis()))}};return target}
    fun undoSplit(left:String,right:String):Int{val pair=ManualFacePair.ordered(left,right);return dao.removeCannotLink(pair.first,pair.second)}
    private fun groupsForAll()=dao.decisions().filter{it.personId!=null}.associate{it.anchorId to requireNotNull(it.personId)}
    private fun ensure(id:String){if(dao.person(id)==null)dao.savePerson(PersonIdentityRecord(id,null,System.currentTimeMillis()))}
    private fun wouldCycle(from:String,into:String):Boolean{var id=into;val merges=dao.merges().associate{it.fromPersonId to it.intoPersonId};repeat(merges.size+1){if(id==from)return true;id=merges[id]?:return false};return true}
}
