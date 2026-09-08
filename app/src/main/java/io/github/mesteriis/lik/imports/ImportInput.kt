package io.github.mesteriis.lik.imports

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException

object ImportInput {
    const val MAX_PHOTOS = 50

    fun uris(intent: Intent): List<Uri> = try {
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(intent.data)
        }
        val clip = intent.clipData
        if (streams.size > MAX_PHOTOS || (clip?.itemCount ?: 0) > MAX_PHOTOS) emptyList()
        else {
            val clipped = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
            val candidates = if (intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE) && streams.isNotEmpty()) streams
                else if (clipped.isNotEmpty()) clipped else streams
            if (candidates.any { it.scheme != "content" }) emptyList() else candidates.distinct()
        }
    } catch (_: BadParcelableException) { emptyList() }
    catch (_: ClassCastException) { emptyList() }
}
