package io.github.mesteriis.lik.settings

import android.app.Activity
import android.content.Intent
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
    private lateinit var content: LinearLayout
    private lateinit var catalog: ModelCatalog
    private var subscription: AutoCloseable? = null
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = ModelCatalog.get(this)
        content = LinearLayout(this).apply {
            id = R.id.ai_settings_content; orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply { addView(content); applySystemBarInsets() }
        setContentView(scroll)
        subscription = catalog.observe { runOnUiThread { render(it) } }
    }

    override fun onDestroy() { subscription?.close(); io.shutdownNow(); super.onDestroy() }

    private fun render(state: CatalogSnapshot) {
        content.removeAllViews()
        button(getString(R.string.back)) { finish() }
        heading(getString(R.string.ai_settings_title), 30f)
        label(getString(R.string.ai_download_disclosure))
        ProfileId.entries.forEach { profileCard(it, state) }
        heading(getString(R.string.ai_features), 22f)
        featureSwitch(AiFeature.SEARCH, R.string.ai_feature_search, R.id.ai_feature_search, state)
        featureSwitch(AiFeature.OCR, R.string.ai_feature_ocr, R.id.ai_feature_ocr, state)
        featureSwitch(AiFeature.PEOPLE, R.string.ai_feature_people, R.id.ai_feature_people, state)
        val active = state.active
        if (active != null && AiFeature.SEARCH in state.enabledFeatures) {
            val generation = state.activeGenerations[AiFeature.SEARCH]?.let(state.generations::get)
            label(getString(R.string.ai_index_coverage, generation?.completed ?: 0, generation?.total ?: 0))
            button(getString(R.string.ai_process_now)) { AiIndexWorker.enqueue(this, active, manual = true) }
            button(getString(R.string.ai_pause_indexing)) { AiIndexWorker.pause(this, active) }
        }
        aiGate()
    }

    private fun profileCard(profile: ProfileId, state: CatalogSnapshot) {
        val spec = catalog.trusted.profiles.getValue(profile)
        val profileState = state.profile(profile)
        val name = getString(when (profile) {
            ProfileId.COMPACT -> R.string.ai_profile_compact
            ProfileId.BALANCED -> R.string.ai_profile_balanced
            ProfileId.EXTENDED -> R.string.ai_profile_extended
        })
        val marker = when { state.active == profile -> getString(R.string.ai_active); state.pending?.profile == profile -> getString(R.string.ai_preparing); else -> "" }
        heading("$name${marker.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()}", 20f,
            when (profile) { ProfileId.COMPACT -> R.id.ai_profile_compact; ProfileId.BALANCED -> R.id.ai_profile_balanced; ProfileId.EXTENDED -> R.id.ai_profile_extended })
        label(getString(R.string.ai_profile_components, spec.componentIds.joinToString(", ")))
        label(getString(R.string.ai_profile_size, formatBytes(spec.uniqueBytes)))
        label(getString(R.string.ai_profile_state, profileState.phase.name.lowercase(Locale.getDefault()).replace('_', ' ')))
        if (profileState.totalBytes > 0) label(getString(R.string.ai_download_progress, profileState.completedBytes, profileState.totalBytes))
        profileState.error?.let { label(getString(R.string.ai_error, it)) }
        when (profileState.phase) {
            ProfilePhase.NOT_INSTALLED, ProfilePhase.ERROR -> button(getString(if (profileState.phase == ProfilePhase.ERROR) R.string.retry else R.string.download)) {
                catalog.select(profile); ProfileDownloadWorker.enqueue(this, profile)
            }
            ProfilePhase.DOWNLOADING, ProfilePhase.VERIFYING, ProfilePhase.SELF_TESTING -> {
                button(getString(R.string.pause)) { ProfileDownloadControls.pause(this, profile) }
                button(getString(R.string.cancel)) { ProfileDownloadControls.cancel(this, profile) }
            }
            ProfilePhase.PAUSED -> {
                button(getString(R.string.resume)) { ProfileDownloadControls.resume(this, profile) }
                button(getString(R.string.cancel)) { ProfileDownloadControls.cancel(this, profile) }
            }
            ProfilePhase.INSTALLED -> {
                button(getString(R.string.ai_use_profile)) {
                    val next = catalog.select(profile)
                    if (next.pending != null && AiFeature.SEARCH in next.enabledFeatures) AiIndexWorker.enqueue(this, profile, manual = true)
                }
                button(getString(R.string.ai_remove_profile)) { io.execute {
                    val bytes = ModelMaintenance.removeInactive(this, profile)
                    runOnUiThread { Toast.makeText(this, getString(R.string.ai_freed_space, formatBytes(bytes)), Toast.LENGTH_LONG).show() }
                } }
            }
            ProfilePhase.PREPARING -> {
                button(getString(R.string.ai_process_now)) { AiIndexWorker.enqueue(this, profile, manual = true) }
                button(getString(R.string.cancel)) {
                    AiIndexWorker.pause(this, profile)
                    catalog.cancelPreparation(profile)
                }
            }
            ProfilePhase.ACTIVE -> Unit
        }
    }

    private fun featureSwitch(feature: AiFeature, title: Int, id: Int, state: CatalogSnapshot) {
        Switch(this).also { value ->
            value.id = id; value.text = getString(title); value.isChecked = feature in state.enabledFeatures
            value.setPadding(0, 8, 0, 8)
            value.setOnCheckedChangeListener { _, enabled ->
                if (enabled == (feature in catalog.snapshot().enabledFeatures)) return@setOnCheckedChangeListener
                val next = catalog.setFeature(feature, enabled)
                val profile = next.active ?: next.selected
                if (enabled && feature == AiFeature.SEARCH && next.profile(profile).phase != ProfilePhase.NOT_INSTALLED)
                    AiIndexWorker.enqueue(this, profile, manual = true)
            }
            content.addView(value, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun aiGate() {
        heading(getString(R.string.aigate_title), 22f)
        label(getString(R.string.aigate_disclosure))
        val settings = AiGateSettings(this)
        Switch(this).also { value -> value.id = R.id.aigate_enabled; value.text = getString(R.string.aigate_opt_in)
            value.isChecked = settings.enabled; value.setOnCheckedChangeListener { _, checked -> settings.enabled = checked }; content.addView(value) }
        val port = EditText(this).also { value -> value.id = R.id.aigate_port; value.hint = getString(R.string.aigate_port)
            value.inputType = InputType.TYPE_CLASS_NUMBER; value.setText(String.format(Locale.ROOT, "%d", settings.port)); content.addView(value, LinearLayout.LayoutParams(-1, -2)) }
        button(getString(R.string.aigate_check), R.id.aigate_check) {
            if (!settings.enabled) { Toast.makeText(this, R.string.aigate_enable_first, Toast.LENGTH_LONG).show(); return@button }
            val configured = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
            if (configured == null) { port.error = getString(R.string.aigate_invalid_port); return@button }
            settings.port = configured
            io.execute {
                val result = runCatching { AiGateEndpoint(configured).let { it to AiGateClient(it).health() } }.getOrNull()
                    ?: AiGateClient.discover()
                runOnUiThread { if (result == null) Toast.makeText(this, R.string.aigate_unavailable, Toast.LENGTH_LONG).show()
                    else { settings.port = result.first.port; port.setText(String.format(Locale.ROOT, "%d", result.first.port))
                        Toast.makeText(this, getString(R.string.aigate_connected, result.first.port, result.second.modelCount ?: 0), Toast.LENGTH_LONG).show() } }
            }
        }
        button(getString(R.string.aigate_open)) {
            packageManager.getLaunchIntentForPackage("com.aigate.router")?.let(::startActivity)
                ?: Toast.makeText(this, R.string.aigate_not_installed, Toast.LENGTH_LONG).show()
        }
    }

    private fun heading(text: String, size: Float, id: Int = View.NO_ID) = TextView(this).also {
        it.id = id; it.text = text; it.textSize = size; it.isAccessibilityHeading = true; it.setPadding(0, 16, 0, 6); content.addView(it)
    }
    private fun label(text: String) = TextView(this).also { it.text = text; it.textSize = 15f; it.setPadding(0, 4, 0, 4); content.addView(it) }
    private fun button(text: String, id: Int = View.NO_ID, action: () -> Unit) = Button(this).also {
        it.id = id; it.text = text; it.isAllCaps = false; it.minHeight = (48 * resources.displayMetrics.density).toInt(); it.setOnClickListener { action() }; content.addView(it)
    }
    private fun formatBytes(bytes: Long): String = String.format(Locale.getDefault(), "%.2f GiB", bytes / 1_073_741_824.0)
}
