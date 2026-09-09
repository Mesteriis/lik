package io.github.mesteriis.lik.gallery

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.isVisible
import androidx.core.net.toUri
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.ui.applySystemBarInsets
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import android.widget.EditText
import android.widget.Toast
import io.github.mesteriis.lik.aigate.*
import io.github.mesteriis.lik.catalog.MediaDatabase
import io.github.mesteriis.lik.catalog.MediaSource
import io.github.mesteriis.lik.imports.PhotoLibrary
import java.util.concurrent.Executors
import io.github.mesteriis.lik.ai.OcrRepository
import io.github.mesteriis.lik.ai.AiUiPublicationGuard
import io.github.mesteriis.lik.ai.AiFeature
import io.github.mesteriis.lik.ai.ModelCatalog
import io.github.mesteriis.lik.privacy.*

class PhotoViewerActivity : ComponentActivity() {
    private lateinit var model: PhotoViewerViewModel
    private lateinit var image: ZoomImageView
    private var shownBitmap: android.graphics.Bitmap? = null
    private val aiGateConsent = PhotoSendConsent()
    private val aiGateIo = Executors.newSingleThreadExecutor()
    private val ocrIo = Executors.newSingleThreadExecutor()
    private var currentMediaId: String? = null
    private var currentRevision: Long = -1
    private var aiGateRequest = 0L
    private var ocrRequest = 0L
    private var currentRequiredRevealEpoch:Long?=null
    @Volatile private var aiGateClient: AiGateClient? = null
    private var aiGateSettingsSubscription: AutoCloseable? = null
    private var privacySubscription:AutoCloseable?=null
    private val ocrInvalidation by lazy { object:androidx.room.InvalidationTracker.Observer("media","ai_media_exposure","ai_ocr_result") {
        override fun onInvalidated(tables:Set<String>) { runOnUiThread { currentMediaId?.let { loadOcr(it,currentRevision) } } }
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)
        findViewById<View>(R.id.viewer_root).applySystemBarInsets()
        model = ViewModelProvider(this)[PhotoViewerViewModel::class.java]
        image = findViewById(R.id.viewer_image)
        image.onNavigate = model::move
        findViewById<View>(R.id.viewer_back).setOnClickListener { finish() }
        findViewById<View>(R.id.viewer_previous).setOnClickListener { model.move(-1) }
        findViewById<View>(R.id.viewer_next).setOnClickListener { model.move(1) }
        findViewById<View>(R.id.viewer_info).setOnClickListener {
            findViewById<View>(R.id.photo_details).apply {
                isVisible = !isVisible
            }
        }
        findViewById<View>(R.id.viewer_delete).setOnClickListener { confirmDelete() }
        findViewById<View>(R.id.viewer_aigate).setOnClickListener { sendToAiGate() }
        findViewById<View>(R.id.viewer_mark_safe).setOnClickListener{setSensitiveDecision(SensitiveDecision.SAFE)}
        findViewById<View>(R.id.viewer_mark_sensitive).setOnClickListener{setSensitiveDecision(SensitiveDecision.SENSITIVE)}
        findViewById<View>(R.id.viewer_copy_ocr).setOnClickListener {
            val value=findViewById<TextView>(R.id.viewer_ocr_text).text
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.ocr_text_title),value))
            Toast.makeText(this,R.string.ocr_text_copied,Toast.LENGTH_SHORT).show()
        }
        aiGateSettingsSubscription = AiGateSettings(this).observeEnabled { enabled ->
            if (!enabled) { aiGateRequest++; aiGateConsent.clear(); aiGateClient?.cancel(); aiGateClient = null }
        }
        model.state.observe(this, ::render)
        privacySubscription=SensitiveMediaSession.current.observe{snapshot->runOnUiThread{privacyChanged(snapshot)}}
        model.start(intent.getStringExtra(EXTRA_PHOTO_ID))
    }

    private fun render(state: ViewerState) {
        state.cursor?.current?.let { photo ->
            if(!SensitiveImagePublication.accepts(MediaDatabase.get(this),photo,state.requiredRevealEpoch)) {
                render(ViewerState(error=true))
                return
            }
        }
        state.requiredRevealEpoch?.let{epoch->
            if(!SensitiveMediaSession.current.accepts(epoch)){
                image.setImageDrawable(null);shownBitmap=null;currentMediaId=null;currentRevision=-1;finish();return
            }
        }
        currentRequiredRevealEpoch=state.requiredRevealEpoch
        state.cursor?.current?.id?.let { id ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PHOTO_ID, id))
        }
        findViewById<ProgressBar>(R.id.viewer_progress).visibility = if (state.loading || state.deleting) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.viewer_error).visibility = if (state.error) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.viewer_previous).isEnabled = state.cursor?.hasPrevious == true && !state.loading && !state.deleting
        findViewById<Button>(R.id.viewer_next).isEnabled = state.cursor?.hasNext == true && !state.loading && !state.deleting
        findViewById<Button>(R.id.viewer_delete).apply {
            visibility = if (state.cursor?.current?.canDeleteCopy == true) View.VISIBLE else View.GONE
            isEnabled = state.cursor?.current?.canDeleteCopy == true && !state.loading && !state.deleting && !state.error
        }
        val currentId = state.cursor?.current?.id
        val nextRevision = state.cursor?.current?.sourceRevision ?: -1
        val changedPhoto = currentMediaId != currentId || currentRevision != nextRevision
        if (changedPhoto) {
            aiGateRequest++
            aiGateConsent.clear()
            aiGateClient?.cancel()
            aiGateClient = null
        }
        currentMediaId = currentId
        currentRevision = nextRevision
        if (changedPhoto) loadOcr(currentId,nextRevision)
        if (image.tag != currentId || shownBitmap !== state.bitmap) {
            image.tag = currentId
            shownBitmap = state.bitmap
            if (state.bitmap == null) image.setImageDrawable(null) else image.setImageBitmap(state.bitmap)
        }
        findViewById<TextView>(R.id.photo_details).text = state.details?.let(::formatDetails).orEmpty()
        if (!state.loading && !state.deleting && state.cursor?.photos?.isEmpty() == true) {
            setResult(RESULT_PHOTO_DELETED)
            finish()
        }
    }

    override fun onDestroy() {
        aiGateRequest++
        aiGateConsent.clear()
        aiGateClient?.cancel()
        aiGateSettingsSubscription?.close()
        aiGateIo.shutdownNow()
        ocrRequest++
        ocrIo.shutdownNow()
        privacySubscription?.close()
        super.onDestroy()
    }

    private fun loadOcr(mediaId:String?, revision:Long){
        val panel=findViewById<View>(R.id.viewer_ocr_panel);val text=findViewById<TextView>(R.id.viewer_ocr_text)
        panel.visibility=View.GONE;text.text="";val id=mediaId?:return;val request=++ocrRequest
        ocrIo.execute { val result=runCatching{OcrRepository(this).text(id)}.getOrNull();runOnUiThread{
            val generation=ModelCatalog.get(this).snapshot().activeGenerations[AiFeature.OCR]
            val valid=request==ocrRequest&&currentMediaId==id&&currentRevision==revision&&!isDestroyed&&generation!=null&&
                result?.let{it.contentRevision==revision&&AiUiPublicationGuard.ocr(MediaDatabase.get(this),generation,it)}==true
            if(!valid){text.text="";panel.visibility=View.GONE;return@runOnUiThread}
            val value=requireNotNull(result).displayText;text.text=value;panel.visibility=if(value.isBlank())View.GONE else View.VISIBLE
        }}
    }

    override fun onResume() {
        super.onResume()
        currentMediaId?.let { loadOcr(it,currentRevision) }
    }

    override fun onStop() {
        MediaDatabase.get(this).invalidationTracker.removeObserver(ocrInvalidation)
        aiGateRequest++
        aiGateConsent.clear()
        aiGateClient?.cancel()
        aiGateClient = null
        super.onStop()
    }

    private fun privacyChanged(snapshot:RevealSnapshot){
        if(snapshot.revealed)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        findViewById<View>(R.id.viewer_mark_safe).visibility=if(snapshot.revealed)View.VISIBLE else View.GONE
        findViewById<View>(R.id.viewer_mark_sensitive).visibility=if(snapshot.revealed)View.VISIBLE else View.GONE
        if(currentRequiredRevealEpoch?.let{!SensitiveMediaSession.current.accepts(it)}==true){
            aiGateRequest++;ocrRequest++;aiGateConsent.clear();aiGateClient?.cancel();image.setImageDrawable(null);shownBitmap=null;finish()
        }
    }

    private fun setSensitiveDecision(decision:SensitiveDecision){
        val id=currentMediaId?:return
        val reveal=SensitiveMediaSession.current.snapshot()
        val revision=currentRevision
        ocrIo.execute {
            val changed=SensitiveMediaRepository(this).setManual(id,decision,reveal,expectedRevision=revision)
            runOnUiThread {
                if(changed&&currentMediaId==id&&decision==SensitiveDecision.SENSITIVE){image.setImageDrawable(null);shownBitmap=null;finish()}
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MediaDatabase.get(this).invalidationTracker.addObserver(ocrInvalidation)
    }

    private fun sendToAiGate() {
        val settings = AiGateSettings(this)
        if (!settings.enabled) { Toast.makeText(this, R.string.aigate_enable_in_settings, Toast.LENGTH_LONG).show(); return }
        val mediaId = currentMediaId ?: return
        val revision = currentRevision
        if (currentRequiredRevealEpoch?.let { !SensitiveMediaSession.current.accepts(it) } == true) {
            Toast.makeText(this, R.string.aigate_task13_required, Toast.LENGTH_LONG).show(); return
        }
        if (shownBitmap == null) return
        val prompt = EditText(this).apply { hint = getString(R.string.aigate_prompt_hint) }
        AlertDialog.Builder(this).setTitle(R.string.aigate_send_title).setMessage(R.string.aigate_send_disclosure)
            .setView(prompt).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.aigate_send_photo) { _, _ ->
                val text = prompt.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val request = ++aiGateRequest
                val token = aiGateConsent.grant(mediaId, revision)
                val privacy = SensitiveMediaSession.current.snapshot()
                aiGateIo.execute {
                    val client = AiGateClient(AiGateEndpoint(settings.port))
                    aiGateClient = client
                    val result = runCatching {
                        require(AiGateSettings(this).enabled) { "AIGATE_DISABLED" }
                        require(client.health().running) { "AIGATE_NOT_RUNNING" }
                        client.models()
                        require(AiGateSettings(this).enabled && request == aiGateRequest) { "AIGATE_CANCELLED" }
                        require(SensitiveMediaRepository(this).mayAccess(mediaId,revision,privacy)){"SENSITIVE_RELOCKED"}
                        val row = requireNotNull(MediaDatabase.get(this).media().get(mediaId)) { "PHOTO_MISSING" }
                        require(row.contentRevision == revision && row.availability.name == "AVAILABLE") { "PHOTO_CHANGED" }
                        val jpeg = if (row.source == MediaSource.DEVICE) {
                            AiGateImage.encode(this, requireNotNull(row.contentUri).toUri(), row.exifOrientation)
                        } else {
                            AiGateImage.encode(PhotoLibrary.store(this).fileFor(requireNotNull(row.privateFileId)))
                        }
                        require(request == aiGateRequest && SensitiveMediaRepository(this).mayAccess(mediaId,revision,privacy)){"SENSITIVE_RELOCKED"}
                        client.chat(token, aiGateConsent, mediaId, revision, request, text, jpeg)
                    }
                    val mayPublish=SensitiveMediaRepository(this).mayAccess(mediaId,revision,privacy)
                    runOnUiThread {
                        if (aiGateClient === client) aiGateClient = null
                        if (request != aiGateRequest || currentMediaId != mediaId || currentRevision != revision) return@runOnUiThread
                        if (!AiGateSettings(this).enabled) return@runOnUiThread
                        if(!mayPublish||SensitiveMediaSession.current.snapshot().epoch!=privacy.epoch)return@runOnUiThread
                        result.onSuccess { reply -> AlertDialog.Builder(this).setTitle(R.string.aigate_reply).setMessage(reply.text)
                            .setPositiveButton(android.R.string.ok, null).show() }
                            .onFailure { Toast.makeText(this, getString(R.string.aigate_send_failed, it.message ?: "error"), Toast.LENGTH_LONG).show() }
                    }
                }
            }.show()
    }

    private fun formatDetails(details: PhotoDetails): String {
        val format = details.mimeType.substringAfter('/').uppercase(Locale.getDefault()).ifEmpty {
            getString(R.string.viewer_unknown)
        }
        val size = getString(R.string.photo_size_bytes, details.bytes / 1_048_576.0)
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(details.addedAt))
        return getString(R.string.photo_details_format, format, details.width, details.height, size, date)
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_photos_title, 1, 1))
            .setMessage(R.string.delete_photos_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> model.deleteCurrent() }
            .show()
    }

    companion object {
        const val EXTRA_PHOTO_ID = "io.github.mesteriis.lik.extra.PHOTO_ID"
        const val EXTRA_RESULT_PHOTO_ID = "io.github.mesteriis.lik.extra.RESULT_PHOTO_ID"
        const val RESULT_PHOTO_DELETED = 2
    }
}
