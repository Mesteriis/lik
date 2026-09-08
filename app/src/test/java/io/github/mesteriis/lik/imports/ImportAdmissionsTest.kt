package io.github.mesteriis.lik.imports

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportAdmissionsTest {
    @Test fun admissionSeparatesMalformedBatchesFromBusyImportsAndAssignsOperationIds() {
        val admissions = ImportAdmissions()

        assertEquals(ImportAdmission.InvalidInput, admissions.admit(photoCount = 0, busy = false))
        assertEquals(ImportAdmission.InvalidInput, admissions.admit(photoCount = 51, busy = false))
        assertEquals(ImportAdmission.Busy, admissions.admit(photoCount = 1, busy = true))
        assertEquals(ImportAdmission.Accepted(operationId = 1), admissions.admit(photoCount = 1, busy = false))
        assertEquals(ImportAdmission.Accepted(operationId = 2), admissions.admit(photoCount = 50, busy = false))
    }

    @Test fun summaryEventIsDeliveredOnceAndStaysConsumedAfterActivityRecreation() {
        val summary = ImportSummary(
            operationId = 4,
            added = 2,
            duplicates = 1,
            failed = 1,
            failureKinds = setOf(ImportFailureKind.INVALID_IMAGE),
        )
        val events = ImportSummaryEvents()

        assertEquals(summary, events.next(summary))
        assertEquals(null, events.next(summary))

        val recreated = ImportSummaryEvents(events.renderedOperationId)
        assertEquals(null, recreated.next(summary))
    }
}
