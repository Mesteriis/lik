package io.github.mesteriis.lik

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.ai.AiExposure
import io.github.mesteriis.lik.ai.AiMediaExposureRecord
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.gallery.PhotoViewerViewModel
import io.github.mesteriis.lik.imports.PhotoLibrary
import io.github.mesteriis.lik.privacy.*
import io.github.mesteriis.lik.ui.TimelineAdapter
import io.github.mesteriis.lik.ui.ThumbnailLoader
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class SensitiveImagePublicationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = MediaDatabase.get(context)

    private fun safePhoto(): MediaRecord {
        SensitiveMediaSession.current.relock(RevealRelockReason.EXPLICIT)
        val bitmap = Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        bitmap.recycle()
        val photo = TrashRepository(db,PhotoLibrary.store(context)).importPhoto(bytes.inputStream()).photo
        val row = db.media().get(photo.id)!!
        db.sensitiveMedia().saveManual(SensitiveManualRecord(row.mediaId,row.contentRevision,SensitiveDecision.SAFE,1))
        db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,row.contentRevision,AiExposure.SAFE,1))
        return row
    }

    private fun <T> field(owner: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner) as T
    }

    private fun holdUi(start: () -> Unit, afterQueued: () -> Unit) {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { start();started.countDown();check(release.await(10,TimeUnit.SECONDS)) }
        assertTrue(started.await(5,TimeUnit.SECONDS))
        try { afterQueued() } finally { release.countDown() }
        instrumentation.waitForIdleSync()
    }

    @Test fun lockedThumbnailRejectsRevisionChangedAfterDecodeBeforeUiDelivery() {
        val row=safePhoto()
        lateinit var adapter:TimelineAdapter
        var published=false
        val image=object:ImageView(context) {
            override fun setImageBitmap(bitmap:Bitmap?) { if(bitmap!=null)published=true;super.setImageBitmap(bitmap) }
        }
        instrumentation.runOnMainSync { adapter=TimelineAdapter(context,{},{},{},java.time.ZoneId.systemDefault()) }
        val method=TimelineAdapter::class.java.getDeclaredMethod("load",io.github.mesteriis.lik.gallery.GalleryPhoto::class.java,
            ImageView::class.java,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        try {
            holdUi({ method.invoke(adapter,row.toGalleryPhoto(PhotoLibrary.store(context)::fileFor),image,640,false) }) {
                val loader=field<ThumbnailLoader<Bitmap>>(adapter,"thumbnailLoader")
                val deadline=System.nanoTime()+5_000_000_000
                while(loader.snapshot().active>0&&System.nanoTime()<deadline)Thread.sleep(10)
                assertEquals(0,loader.snapshot().active)
                db.media().upsert(row.copy(contentRevision=row.contentRevision+1))
            }
            assertFalse("Previously SAFE thumbnail must not publish after its revision changes",published)
        } finally { instrumentation.runOnMainSync { adapter.close() };PhotoLibrary.store(context).fileFor(row.privateFileId!!).delete() }
    }

    @Test fun lockedViewerRejectsRevisionChangedAfterDecodeBeforeUiDelivery() {
        val row=safePhoto();val store=ViewModelStore()
        lateinit var model:PhotoViewerViewModel
        instrumentation.runOnMainSync { model=PhotoViewerViewModel(context.applicationContext as Application);store.put("viewer",model) }
        try {
            holdUi({ model.start(row.mediaId) }) {
                field<ExecutorService>(model,"worker").submit {}.get(5,TimeUnit.SECONDS)
                val current=db.media().get(row.mediaId)!!
                db.media().upsert(current.copy(contentRevision=current.contentRevision+1))
            }
            instrumentation.runOnMainSync { assertNull("Previously SAFE viewer must reject stale queued pixels",model.state.value?.bitmap) }
        } finally { instrumentation.runOnMainSync { store.clear() };PhotoLibrary.store(context).fileFor(row.privateFileId!!).delete() }
    }

    @Test fun lockedViewerClearsAlreadyPublishedSafePhotoWhenPolicyChanges() {
        val row=safePhoto();val store=ViewModelStore()
        lateinit var model:PhotoViewerViewModel
        instrumentation.runOnMainSync { model=PhotoViewerViewModel(context.applicationContext as Application);store.put("viewer",model);model.start(row.mediaId) }
        try {
            field<ExecutorService>(model,"worker").submit {}.get(5,TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertNotNull(model.state.value?.bitmap) }
            db.sensitiveMedia().saveManual(SensitiveManualRecord(row.mediaId,row.contentRevision,SensitiveDecision.SENSITIVE,2))
            db.ocrPeople().saveExposure(AiMediaExposureRecord(row.mediaId,row.contentRevision,AiExposure.SENSITIVE,2))
            db.invalidationTracker.refreshVersionsSync()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertNull("Policy changes must clear already displayed SAFE pixels",model.state.value?.bitmap) }
        } finally { instrumentation.runOnMainSync { store.clear() };PhotoLibrary.store(context).fileFor(row.privateFileId!!).delete() }
    }
}
