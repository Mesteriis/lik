package io.github.mesteriis.lik

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mesteriis.lik.imports.ImportInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportInputTest {
    private val first = Uri.parse("content://photos/first")
    private val second = Uri.parse("content://photos/second")

    @Test fun pickerIncludesAllClipItemsWhenDataAlsoContainsFirstItem() {
        val result = Intent().apply {
            data = first
            clipData = ClipData.newRawUri("photos", first).apply { addItem(ClipData.Item(second)) }
        }
        assertEquals(listOf(first, second), ImportInput.uris(result))
    }

    @Test fun rejectsFileUrisAndOversizedShareAndDeduplicatesStreams() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, first, second))
        }
        assertEquals(listOf(first, second), ImportInput.uris(intent))
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(List(51) { first }))
        assertTrue(ImportInput.uris(intent).isEmpty())
        assertTrue(ImportInput.uris(Intent().setData(Uri.parse("file:///private/file"))).isEmpty())
    }
}
