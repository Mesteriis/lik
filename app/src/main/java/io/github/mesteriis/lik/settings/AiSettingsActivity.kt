package io.github.mesteriis.lik.settings

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.ai.*
import io.github.mesteriis.lik.aigate.*
import io.github.mesteriis.lik.ui.applySystemBarInsets
import java.util.Locale
import java.util.concurrent.Executors

class AiSettingsActivity : Activity() {
    private enum class ProfileAction { DOWNLOAD, PAUSE, CANCEL, RESUME, USE, REMOVE, PROCESS }
    private data class ProfileViews(
        val heading: TextView,
        val components: TextView,
        val size: TextView,
        val state: TextView,
        val progress: TextView,
        val error: TextView,
        val actions: Map<ProfileAction, Button>,
    )

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var catalog: ModelCatalog
    private lateinit var indexCoverage: TextView
    private lateinit var indexActions: LinearLayout
    private lateinit var processIndex: Button
    private lateinit var pauseIndex: Button
    private lateinit var aiGatePort: EditText
    private val profileViews = mutableMapOf<ProfileId, ProfileViews>()
    private val featureViews = mutableMapOf<AiFeature, Switch>()
    private var subscription: AutoCloseable? = null
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var aiGateClient: AiGateClient? = null
    private var applyingState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = ModelCatalog.get(this)
        // OCR and People are intentionally visible but unavailable until Task 11 supplies real generations.
        listOf(AiFeature.OCR, AiFeature.PEOPLE).forEach { feature ->
            if (feature in catalog.snapshot().enabledFeatures) catalog.setFeature(feature, false)
        }
        content = LinearLayout(this).apply {
            id = R.id.ai_settings_content
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scroll = ScrollView(this).apply { addView(content); applySystemBarInsets() }
        setContentView(scroll)
        buildUi(savedInstanceState)
        subscription = catalog.observe { state -> runOnUiThread { applyState(state) } }
        savedInstanceState?.getInt(SCROLL_Y)?.let { value -> scroll.post { scroll.scrollTo(0, value) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PORT_TEXT, aiGatePort.text.toString())
        outState.putInt(SCROLL_Y, scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        subscription?.close()
        aiGateClient?.cancel()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(savedState: Bundle?) {
        button(getString(R.string.back)) { finish() }
        heading(getString(R.string.ai_settings_title), 30f)
        label(getString(R.string.ai_download_disclosure))
        ProfileId.entries.forEach { profile ->
            val id = when (profile) {
                ProfileId.COMPACT -> R.id.ai_profile_compact
                ProfileId.BALANCED -> R.id.ai_profile_balanced
                ProfileId.EXTENDED -> R.id.ai_profile_extended
            }
            val heading = heading("", 20f, id)
            val components = label("")
            val size = label("")
            val state = label("")
            val progress = label("")
            val error = label("")
            val actionContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
            val actions = ProfileAction.entries.associateWith { action ->
                actionButton(actionContainer, "") { performProfileAction(profile, action) }.apply { visibility = View.GONE }
            }
            profileViews[profile] = ProfileViews(heading, components, size, state, progress, error, actions)
        }
        heading(getString(R.string.ai_features), 22f)
        featureSwitch(AiFeature.SEARCH, R.string.ai_feature_search, R.id.ai_feature_search, available = true)
        featureSwitch(AiFeature.OCR, R.string.ai_feature_ocr, R.id.ai_feature_ocr, available = false)
        featureSwitch(AiFeature.PEOPLE, R.string.ai_feature_people, R.id.ai_feature_people, available = false)
        indexCoverage = label("")
        indexActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
        processIndex = actionButton(indexActions, getString(R.string.ai_process_now)) {
            catalog.snapshot().active?.let { AiIndexWorker.enqueue(this, it, manual = true) }
        }
        pauseIndex = actionButton(indexActions, getString(R.string.ai_pause_indexing)) {
            catalog.snapshot().active?.let { AiIndexWorker.pause(this, it) }
        }
        buildAiGate(savedState)
    }

    private fun applyState(snapshot: CatalogSnapshot) {
        applyingState = true
        try {
            ProfileId.entries.forEach { profile -> applyProfile(profile, snapshot) }
            val requestedFeatures = snapshot.pending?.enabled ?: snapshot.enabledFeatures
            featureViews.forEach { (feature, view) -> view.isChecked = feature in requestedFeatures }
            val active = snapshot.active
            val visible = active != null && AiFeature.SEARCH in snapshot.enabledFeatures
            indexCoverage.visibility = if (visible) View.VISIBLE else View.GONE
            indexActions.visibility = if (visible) View.VISIBLE else View.GONE
            active?.takeIf { visible }?.let {
                val generation = snapshot.activeGenerations[AiFeature.SEARCH]?.let(snapshot.generations::get)
                indexCoverage.text = getString(R.string.ai_index_coverage, generation?.completed ?: 0, generation?.total ?: 0)
            }
        } finally { applyingState = false }
    }

    private fun applyProfile(profile: ProfileId, snapshot: CatalogSnapshot) {
        val views = profileViews.getValue(profile)
        val spec = catalog.trusted.profiles.getValue(profile)
        val value = snapshot.profile(profile)
        val name = getString(when (profile) {
            ProfileId.COMPACT -> R.string.ai_profile_compact
            ProfileId.BALANCED -> R.string.ai_profile_balanced
            ProfileId.EXTENDED -> R.string.ai_profile_extended
        })
        val markers = listOfNotNull(
            getString(R.string.ai_active).takeIf { snapshot.active == profile },
            getString(R.string.ai_preparing).takeIf { snapshot.pending?.profile == profile },
        )
        val marker = markers.joinToString(" · ")
        views.heading.text = if (marker.isEmpty()) name else getString(R.string.ai_profile_heading_status, name, marker)
        views.components.text = getString(R.string.ai_profile_components, spec.componentIds.joinToString(", "))
        views.size.text = getString(R.string.ai_profile_size, formatBytes(spec.uniqueBytes))
        val displayedPhase = if (snapshot.pending?.profile == profile && value.phase == ProfilePhase.ACTIVE) ProfilePhase.PREPARING else value.phase
        views.state.text = getString(R.string.ai_profile_state, phaseLabel(displayedPhase))
        views.progress.visibility = if (value.totalBytes > 0) View.VISIBLE else View.GONE
        views.progress.text = getString(R.string.ai_download_progress, value.completedBytes, value.totalBytes)
        views.error.visibility = if (value.error == null) View.GONE else View.VISIBLE
        views.error.text = value.error?.let { getString(R.string.ai_error, it) }.orEmpty()
        views.actions.values.forEach { it.visibility = View.GONE }
        fun show(action: ProfileAction, text: Int) = views.actions.getValue(action).apply {
            setText(text); visibility = View.VISIBLE
        }
        when (displayedPhase) {
            ProfilePhase.NOT_INSTALLED -> show(ProfileAction.DOWNLOAD, R.string.download)
            ProfilePhase.ERROR -> show(ProfileAction.DOWNLOAD, R.string.retry)
            ProfilePhase.DOWNLOADING, ProfilePhase.VERIFYING, ProfilePhase.SELF_TESTING -> {
                show(ProfileAction.PAUSE, R.string.pause); show(ProfileAction.CANCEL, R.string.cancel)
            }
            ProfilePhase.PAUSED -> {
                show(ProfileAction.RESUME, R.string.resume); show(ProfileAction.CANCEL, R.string.cancel)
            }
            ProfilePhase.INSTALLED -> {
                show(ProfileAction.USE, R.string.ai_use_profile); show(ProfileAction.REMOVE, R.string.ai_remove_profile)
            }
            ProfilePhase.PREPARING -> {
                show(ProfileAction.PROCESS, R.string.ai_process_now); show(ProfileAction.CANCEL, R.string.cancel)
            }
            ProfilePhase.ACTIVE -> Unit
        }
    }

    private fun performProfileAction(profile: ProfileId, action: ProfileAction) {
        when (action) {
            ProfileAction.DOWNLOAD -> { catalog.select(profile); ProfileDownloadWorker.enqueue(this, profile) }
            ProfileAction.PAUSE -> ProfileDownloadControls.pause(this, profile)
            ProfileAction.CANCEL -> if (catalog.snapshot().profile(profile).phase == ProfilePhase.PREPARING) {
                io.execute { AiIndexWorker.discardPreparation(this, profile) }
            } else ProfileDownloadControls.cancel(this, profile)
            ProfileAction.RESUME -> ProfileDownloadControls.resume(this, profile)
            ProfileAction.USE -> {
                val next = catalog.select(profile)
                val requested = next.pending?.enabled ?: next.enabledFeatures
                if (next.pending != null && AiFeature.SEARCH in requested) AiIndexWorker.enqueue(this, profile, manual = true)
            }
            ProfileAction.REMOVE -> io.execute {
                val bytes = ModelMaintenance.removeInactive(this, profile)
                runOnUiThread { Toast.makeText(this, getString(R.string.ai_freed_space, formatBytes(bytes)), Toast.LENGTH_LONG).show() }
            }
            ProfileAction.PROCESS -> AiIndexWorker.enqueue(this, profile, manual = true)
        }
    }

    private fun featureSwitch(feature: AiFeature, title: Int, id: Int, available: Boolean) {
        Switch(this).also { value ->
            value.id = id
            value.text = if (available) getString(title) else getString(R.string.ai_feature_unavailable, getString(title))
            value.isEnabled = available
            value.setPadding(0, 8, 0, 8)
            value.setOnCheckedChangeListener { _, enabled ->
                val before = catalog.snapshot()
                val requested = before.pending?.enabled ?: before.enabledFeatures
                if (applyingState || !available || enabled == (feature in requested)) return@setOnCheckedChangeListener
                val next = catalog.setFeature(feature, enabled)
                val profile = next.pending?.profile ?: next.active ?: next.selected
                if (enabled && feature == AiFeature.SEARCH && next.profile(profile).phase != ProfilePhase.NOT_INSTALLED) {
                    AiIndexWorker.enqueue(this, profile, manual = true)
                }
            }
            featureViews[feature] = value
            content.addView(value, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildAiGate(savedState: Bundle?) {
        heading(getString(R.string.aigate_title), 22f)
        label(getString(R.string.aigate_disclosure))
        val settings = AiGateSettings(this)
        Switch(this).also { value ->
            value.id = R.id.aigate_enabled
            value.text = getString(R.string.aigate_opt_in)
            value.isChecked = settings.enabled
            value.setOnCheckedChangeListener { _, checked ->
                settings.enabled = checked
                if (!checked) { aiGateClient?.cancel(); aiGateClient = null }
            }
            content.addView(value)
        }
        aiGatePort = EditText(this).also { value ->
            value.id = R.id.aigate_port
            value.hint = getString(R.string.aigate_port)
            value.inputType = InputType.TYPE_CLASS_NUMBER
            value.setText(savedState?.getString(PORT_TEXT) ?: String.format(Locale.ROOT, "%d", settings.port))
            content.addView(value, LinearLayout.LayoutParams(-1, -2))
        }
        button(getString(R.string.aigate_check), R.id.aigate_check) {
            if (!settings.enabled) { Toast.makeText(this, R.string.aigate_enable_first, Toast.LENGTH_LONG).show(); return@button }
            val configured = aiGatePort.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
            if (configured == null) { aiGatePort.error = getString(R.string.aigate_invalid_port); return@button }
            settings.port = configured
            io.execute {
                val result = runCatching {
                    val endpoint = AiGateEndpoint(configured)
                    val client = AiGateClient(endpoint)
                    aiGateClient = client
                    val health = client.health()
                    require(health.running) { "AIGATE_NOT_RUNNING" }
                    client.models()
                    endpoint to health
                }.getOrNull() ?: AiGateClient.discover()?.also { pair ->
                    val client = AiGateClient(pair.first)
                    aiGateClient = client
                    runCatching { client.models() }.getOrNull() ?: return@execute
                }
                runOnUiThread {
                    aiGateClient = null
                    if (result == null) Toast.makeText(this, R.string.aigate_unavailable, Toast.LENGTH_LONG).show()
                    else {
                        settings.port = result.first.port
                        aiGatePort.setText(String.format(Locale.ROOT, "%d", result.first.port))
                        Toast.makeText(this, getString(R.string.aigate_connected, result.first.port, result.second.modelCount ?: 0), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        button(getString(R.string.aigate_open)) {
            packageManager.getLaunchIntentForPackage("com.aigate.router")?.let(::startActivity)
                ?: Toast.makeText(this, R.string.aigate_not_installed, Toast.LENGTH_LONG).show()
        }
    }

    private fun phaseLabel(phase: ProfilePhase) = getString(when (phase) {
        ProfilePhase.NOT_INSTALLED -> R.string.ai_state_not_installed
        ProfilePhase.DOWNLOADING -> R.string.ai_state_downloading
        ProfilePhase.PAUSED -> R.string.ai_state_paused
        ProfilePhase.VERIFYING -> R.string.ai_state_verifying
        ProfilePhase.SELF_TESTING -> R.string.ai_state_self_testing
        ProfilePhase.PREPARING -> R.string.ai_state_preparing
        ProfilePhase.INSTALLED -> R.string.ai_state_installed
        ProfilePhase.ACTIVE -> R.string.ai_state_active
        ProfilePhase.ERROR -> R.string.ai_state_error
    })

    private fun heading(text: String, size: Float, id: Int = View.NO_ID) = TextView(this).also {
        it.id = id; it.text = text; it.textSize = size; it.isAccessibilityHeading = true; it.setPadding(0, 16, 0, 6); content.addView(it)
    }
    private fun label(text: String) = TextView(this).also { it.text = text; it.textSize = 15f; it.setPadding(0, 4, 0, 4); content.addView(it) }
    private fun button(text: String, id: Int = View.NO_ID, action: () -> Unit) = Button(this).also {
        it.id = id; it.text = text; it.isAllCaps = false; it.minHeight = (48 * resources.displayMetrics.density).toInt(); it.setOnClickListener { action() }; content.addView(it)
    }
    private fun actionButton(parent: LinearLayout, text: String, action: () -> Unit) = Button(this).also {
        it.text = text; it.isAllCaps = false; it.minHeight = (48 * resources.displayMetrics.density).toInt(); it.setOnClickListener { action() }; parent.addView(it)
    }
    private fun formatBytes(bytes: Long): String = String.format(Locale.getDefault(), "%.2f GiB", bytes / 1_073_741_824.0)

    companion object { private const val PORT_TEXT = "port-text"; private const val SCROLL_Y = "scroll-y" }
}
