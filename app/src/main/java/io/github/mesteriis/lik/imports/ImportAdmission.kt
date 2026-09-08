package io.github.mesteriis.lik.imports

sealed interface ImportAdmission {
    data class Accepted(val operationId: Long) : ImportAdmission
    data object InvalidInput : ImportAdmission
    data object Busy : ImportAdmission
}

data class ImportSummary(
    val operationId: Long,
    val added: Int,
    val duplicates: Int,
    val failed: Int,
    val failureKinds: Set<ImportFailureKind>,
    val restored: Int = 0,
)

class ImportSummaryEvents(initialRenderedOperationId: Long? = null) {
    var renderedOperationId: Long? = initialRenderedOperationId
        private set

    fun next(summary: ImportSummary?): ImportSummary? {
        if (summary == null || summary.operationId == renderedOperationId) return null
        renderedOperationId = summary.operationId
        return summary
    }
}

class ImportAdmissions {
    private var nextOperationId = 1L

    fun admit(photoCount: Int, busy: Boolean): ImportAdmission = when {
        photoCount !in 1..ImportInput.MAX_PHOTOS -> ImportAdmission.InvalidInput
        busy -> ImportAdmission.Busy
        else -> ImportAdmission.Accepted(nextOperationId++)
    }
}
