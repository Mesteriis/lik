package io.github.mesteriis.lik.aigate

object AiGatePhotoBoundary {
    fun maySend(context:android.content.Context,mediaId: String, revision: Long): Boolean {
        require(mediaId.isNotBlank() && revision >= 0)
        return io.github.mesteriis.lik.privacy.SensitiveMediaRepository(context).mayAccess(mediaId,revision)
    }
}
