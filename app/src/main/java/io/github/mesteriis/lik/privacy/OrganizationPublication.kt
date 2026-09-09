package io.github.mesteriis.lik.privacy

import io.github.mesteriis.lik.catalog.CatalogChanges
import io.github.mesteriis.lik.catalog.MediaDatabase

data class OrganizationPublication(val catalogRevision:Long,val reveal:RevealSnapshot) {
    // A single indexed token read at the UI boundary; no row loads, inference or aggregate scans.
    fun current(database:MediaDatabase):Boolean = SensitiveMediaSession.current.snapshot()==reveal &&
        CatalogChanges.revision(database)==catalogRevision

    companion object {
        fun capture(database:MediaDatabase)=OrganizationPublication(CatalogChanges.revision(database),SensitiveMediaSession.current.snapshot())
    }
}
