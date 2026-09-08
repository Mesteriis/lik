package io.github.mesteriis.lik

import android.Manifest
import android.app.Application
import android.provider.MediaStore
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesteriis.lik.gallery.GalleryCatalog
import io.github.mesteriis.lik.imports.ImportState
import io.github.mesteriis.lik.imports.ImportViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GalleryObservationTest {
    @Test fun observerCoalescesChangesStopsQueuedCallbacksAndResumes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: ImportViewModel
        var createdModel: ImportViewModel? = null
        val starts = AtomicInteger()
        val completions = AtomicInteger()
        val scanning = AtomicBoolean()
        val observer = Observer<ImportState> { state ->
            val previous = scanning.getAndSet(state.scanning)
            if (state.scanning && !previous) starts.incrementAndGet()
            if (!state.scanning && previous) completions.incrementAndGet()
        }
        fun changeBurst() {
            repeat(25) { context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null) }
        }
        fun awaitIdle(expectedStarts: Int) {
            val deadline = System.nanoTime() + 10_000_000_000
            while ((starts.get() < expectedStarts || scanning.get()) && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(expectedStarts, starts.get())
            assertFalse(scanning.get())
        }
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[ImportViewModel::class.java]
                createdModel = model
                model.state.observeForever(observer)
                model.refresh(true)
            }
            awaitIdle(1)
            instrumentation.runOnMainSync { changeBurst() }
            awaitIdle(2)
            Thread.sleep(500)
            assertEquals("Burst must produce one reconciliation", 2, starts.get())

            // Notifications enqueue callbacks on the main looper. Stop before those callbacks run.
            instrumentation.runOnMainSync { changeBurst(); model.stopObserving() }
            Thread.sleep(700)
            assertEquals("A queued callback must not restart a stopped observer", 2, starts.get())

            instrumentation.runOnMainSync { model.refresh(true) }
            awaitIdle(3)
            instrumentation.runOnMainSync { changeBurst() }
            awaitIdle(4)

            val finishedBeforeCancellation = completions.get()
            // Hold the real catalog monitor so the worker cannot complete before cancellation.
            synchronized(GalleryCatalog) {
                instrumentation.runOnMainSync { model.refresh(true); model.stopObserving() }
            }
            Thread.sleep(500)
            assertEquals(finishedBeforeCancellation, completions.get())
        } finally {
            instrumentation.runOnMainSync {
                createdModel?.state?.removeObserver(observer)
                owner.viewModelStore.clear()
            }
        }
    }
}
