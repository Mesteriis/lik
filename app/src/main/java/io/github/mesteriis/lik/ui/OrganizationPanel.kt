package io.github.mesteriis.lik.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import io.github.mesteriis.lik.R
import io.github.mesteriis.lik.catalog.*
import io.github.mesteriis.lik.gallery.PhotoViewerActivity
import io.github.mesteriis.lik.ai.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Native, scrollable organization screens; all database work is dispatched off the UI thread. */
class OrganizationPanel(
    private val activity: Activity,
    private val container: LinearLayout,
    private val selected: () -> Set<String>,
    private val toggle: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = OrganizationRepository(MediaDatabase.get(activity))
    private var request = 0
    private var section: GallerySection? = null
    private var active = false
    private var screen = ""
    private var query = CatalogSearch()
    private var offset = 0
    private var resultTitle = ""
    private var form: List<EditText> = emptyList()
    private var source: Spinner? = null
    private var drafts = listOf("", "", "", "", "")
    private var sourceIndex = 0
    private var semanticText = ""
    private var personId: String? = null
    private var content: LinearLayout = container

    fun restore(state: Bundle?) {
        state ?: return
        section = state.getString("organization.section")?.let { runCatching { GallerySection.valueOf(it) }.getOrNull() }
        screen = state.getString("organization.screen").orEmpty()
        drafts = state.getStringArrayList("organization.drafts") ?: drafts
        sourceIndex = state.getInt("organization.source")
        resultTitle = state.getString("organization.title").orEmpty()
        personId = state.getString("organization.person")
        offset = state.getInt("organization.offset")
        val folder = state.getString("organization.volume")?.let {
            DeviceFolder(it, state.getString("organization.bucket").orEmpty(), state.getString("organization.path").orEmpty(), null, 0)
        }
        query = CatalogSearch(name = state.getString("organization.name").orEmpty(),
            from = state.getLong("organization.from").takeIf { state.containsKey("organization.from") },
            until = state.getLong("organization.until").takeIf { state.containsKey("organization.until") },
            tag = state.getString("organization.tag").orEmpty(), albumId = state.getString("organization.album"),
            source = state.getString("organization.filterSource")?.let(MediaSource::valueOf),
            favorites = state.getBoolean("organization.favorites"), folder = folder,
            ocrText = state.getString("organization.ocr").orEmpty(), ocrGenerationId = state.getString("organization.ocrGeneration"))
    }

    fun save(state: Bundle) {
        captureDrafts()
        state.putString("organization.section", section?.name)
        state.putString("organization.screen", screen)
        state.putStringArrayList("organization.drafts", ArrayList(drafts))
        state.putInt("organization.source", sourceIndex)
        state.putString("organization.title", resultTitle)
        state.putString("organization.person", personId)
        state.putInt("organization.offset", offset)
        state.putString("organization.name", query.name)
        query.from?.let { state.putLong("organization.from", it) }
        query.until?.let { state.putLong("organization.until", it) }
        state.putString("organization.tag", query.tag)
        state.putString("organization.album", query.albumId)
        state.putString("organization.filterSource", query.source?.name)
        state.putBoolean("organization.favorites", query.favorites)
        state.putString("organization.ocr", query.ocrText)
        state.putString("organization.ocrGeneration", query.ocrGenerationId)
        query.folder?.let {
            state.putString("organization.volume", it.volumeName)
            state.putString("organization.bucket", it.bucketId)
            state.putString("organization.path", it.relativePath)
        }
    }

    fun show(value: GallerySection) {
        active = true
        if (section != value) {
            captureDrafts()
            section = value
            screen = when (value) { GallerySection.ALBUMS -> "albums"; GallerySection.SEARCH -> "search"; GallerySection.PEOPLE -> "people"; else -> "more" }
        }
        render()
    }

    fun hide() { active = false; request++ }
    fun refresh() { if (active && container.isShown && screen != "search") render() }
    fun close() { request++; scope.cancel() }

    private fun root(title: String) {
        request++
        form = emptyList(); source = null
        container.removeAllViews()
        container.gravity = android.view.Gravity.TOP
        container.setPadding(0, 0, 0, 0)
        val scroll = ScrollView(activity)
        content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        scroll.addView(content)
        container.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        label(title, true)
    }

    private fun label(value: String, heading: Boolean = false) = TextView(activity).also {
        it.text = value; it.textSize = if (heading) 24f else 16f
        it.isAccessibilityHeading = heading
        it.setPadding(0, 8, 0, 8)
        content.addView(it, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(text: String, id: Int = View.NO_ID, action: () -> Unit) = Button(activity).also {
        it.id = id; it.text = text; it.isAllCaps = false
        it.minHeight = (48 * activity.resources.displayMetrics.density).toInt()
        it.setOnClickListener { action() }
        content.addView(it, LinearLayout.LayoutParams(-1, -2))
    }
    private fun text(id: Int) = activity.getString(id)

    private fun <T> load(work: () -> T, render: (T) -> Unit) {
        val expected = request
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { work() }
                if (request == expected) render(value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == expected) label(text(R.string.organization_error)) }
        }
    }

    private fun render() {
        captureDrafts()
        when (screen) {
            "albums" -> albums()
            "search" -> search()
            "results" -> results()
            "semantic_results" -> semanticResults()
            "people" -> people()
            "person" -> person()
            "trash" -> trash()
            "ai" -> {
                activity.startActivity(Intent(activity, io.github.mesteriis.lik.settings.AiSettingsActivity::class.java))
                screen = "more"
                render()
            }
            else -> {
                root(text(R.string.nav_more))
                button(text(R.string.search_title), R.id.organization_search) { screen = "search"; render() }
                button(text(R.string.trash_title), R.id.organization_trash) { screen = "trash"; offset = 0; render() }
                button(text(R.string.ai_settings_title), R.id.organization_ai) { screen = "ai"; render() }
            }
        }
    }

    private fun trash() {
        root(text(R.string.trash_title))
        button(text(R.string.back)) { screen = "more"; render() }
        label(text(R.string.trash_help))
        val db = MediaDatabase.get(activity)
        val trash = TrashRepository(db, io.github.mesteriis.lik.imports.PhotoLibrary.store(activity))
        load({ db.media().trashPage(61, offset) }) { rows ->
            if (rows.isEmpty()) label(text(R.string.trash_empty))
            rows.take(60).forEach { row ->
                val title = row.displayName ?: row.mediaId.takeLast(12)
                label(title, true)
                row.trashedAt?.let { label(activity.getString(R.string.trash_since,
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(it).atZone(libraryZone(activity))))) }
                button(text(R.string.restore_photo)) { mutate { trash.restore(row.mediaId) } }
                button(text(R.string.purge_photo)) {
                    AlertDialog.Builder(activity).setTitle(R.string.purge_photo).setMessage(R.string.purge_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.purge_photo) { _, _ -> mutate { trash.purgeNow(setOf(row.mediaId)) } }.show()
                }
            }
            if (offset > 0) button(text(R.string.previous_page)) { offset = (offset - 60).coerceAtLeast(0); render() }
            if (rows.size > 60) button(text(R.string.next_page)) { offset += 60; render() }
        }
    }

    private fun albums() {
        root(text(R.string.nav_albums))
        button(text(R.string.create_album), R.id.organization_create_album) {
            prompt(R.string.create_album) { name -> mutate { repository.createAlbum(name) } }
        }
        button(text(R.string.favorites_title), R.id.organization_favorites) { open(CatalogSearch(favorites = true), text(R.string.favorites_title)) }
        load({ repository.dao.albums() to repository.dao.folders() }) { (albums, folders) ->
            label(text(R.string.virtual_albums), true)
            if (albums.isEmpty()) label(text(R.string.no_albums))
            albums.forEach { album ->
                val title = "${album.name} · ${album.albumId.take(8)}"
                val row = button(activity.getString(R.string.collection_count, title, album.count)) { open(CatalogSearch(albumId = album.albumId), title) }
                row.setOnLongClickListener {
                    AlertDialog.Builder(activity).setTitle(title).setItems(arrayOf(text(R.string.rename_album), text(R.string.remove_album))) { _, which ->
                        if (which == 0) prompt(R.string.rename_album, album.name) { name -> mutate { repository.dao.renameAlbum(album.albumId, name) } }
                        else AlertDialog.Builder(activity).setMessage(R.string.remove_album_message).setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.remove_album) { _, _ -> mutate { repository.dao.deleteAlbum(album.albumId) } }.show()
                    }.show(); true
                }
            }
            label(text(R.string.device_folders), true)
            if (folders.isEmpty()) label(text(R.string.no_folders))
            folders.forEach { folder ->
                val title = "${folder.name ?: text(R.string.unknown_folder)} · ${folder.volumeName}/${folder.relativePath} · ${folder.bucketId}"
                button(activity.getString(R.string.collection_count, title, folder.count)) { open(CatalogSearch(folder = folder), title) }
            }
            label(text(R.string.albums_help))
        }
    }

    private fun captureDrafts() {
        if (form.isNotEmpty()) drafts = form.map { it.text.toString() }
        source?.let { sourceIndex = it.selectedItemPosition }
    }

    private fun search() {
        root(text(R.string.search_title))
        val fields = listOf(R.string.search_name, R.string.search_tag, R.string.search_from, R.string.search_until, R.string.search_ocr_text)
        form = fields.mapIndexed { index, hint ->
            label(text(hint))
            EditText(activity).also {
                it.id = listOf(R.id.search_name, R.id.search_tag, R.id.search_from, R.id.search_until, R.id.search_ocr)[index]
                it.hint = text(hint); it.inputType = InputType.TYPE_CLASS_TEXT
                it.setText(drafts.getOrElse(index) { "" })
                content.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        }
        label(text(R.string.search_source))
        source = Spinner(activity).also {
            it.id = R.id.search_source
            it.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf(text(R.string.source_all), text(R.string.source_device), text(R.string.source_google)))
            it.setSelection(sourceIndex)
            content.addView(it)
        }
        label(text(R.string.search_date_help))
        button(text(R.string.search_title), R.id.search_submit) {
            captureDrafts()
            try {
                val zone = libraryZone(activity)
                fun date(value: String, end: Boolean): Long? = value.trim().takeIf(String::isNotEmpty)?.let {
                    LocalDate.parse(it).let { day -> if (end) day.plusDays(1) else day }.atStartOfDay(zone).toInstant().toEpochMilli()
                }
                val ocrGeneration = ModelCatalog.get(activity).snapshot().activeGenerations[AiFeature.OCR]
                if (drafts[4].isNotBlank() && ocrGeneration == null) throw IllegalStateException("OCR_NOT_READY")
                open(CatalogSearch(name = drafts[0], tag = drafts[1], from = date(drafts[2], false), until = date(drafts[3], true),
                    source = when (sourceIndex) { 1 -> MediaSource.DEVICE; 2 -> MediaSource.GOOGLE_IMPORT; else -> null },
                    ocrText = drafts[4], ocrGenerationId = ocrGeneration), text(R.string.search_results))
            } catch (_: IllegalArgumentException) { Toast.makeText(activity, R.string.invalid_search_date, Toast.LENGTH_LONG).show() }
            catch (_: java.time.DateTimeException) { Toast.makeText(activity, R.string.invalid_search_date, Toast.LENGTH_LONG).show() }
            catch (_: IllegalStateException) { Toast.makeText(activity, R.string.ocr_not_ready, Toast.LENGTH_LONG).show() }
        }
        label(text(R.string.semantic_search_title), true)
        val semantic = EditText(activity).also {
            it.hint = text(R.string.semantic_search_hint); it.setText(semanticText)
            content.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        button(text(R.string.semantic_search_action)) {
            semanticText = semantic.text.toString().trim()
            if (semanticText.isEmpty()) semantic.error = text(R.string.name_required)
            else { screen = "semantic_results"; render() }
        }
    }

    private fun people() {
        root(text(R.string.people_title))
        val state = ModelCatalog.get(activity).snapshot()
        val generation = state.activeGenerations[AiFeature.PEOPLE]
        if (AiFeature.PEOPLE !in state.enabledFeatures || generation == null) {
            label(text(R.string.people_not_ready)); button(text(R.string.ai_settings_title)) { activity.startActivity(Intent(activity, io.github.mesteriis.lik.settings.AiSettingsActivity::class.java)) }
            return
        }
        val repository = PeopleRepository(MediaDatabase.get(activity))
        load({ repository.groups(generation) to repository.excluded(generation) }) { (groups, excluded) ->
            if (ModelCatalog.get(activity).snapshot().activeGenerations[AiFeature.PEOPLE] != generation) {
                render()
                return@load
            }
            if (groups.isEmpty() && excluded.isEmpty()) label(text(R.string.people_empty))
            groups.forEach { group -> button(activity.resources.getQuantityString(R.plurals.person_group_count, group.faces.size, group.name ?: text(R.string.unnamed_person), group.faces.size)) {
                personId = group.personId; screen = "person"; render()
            } }
            if (excluded.isNotEmpty()) {
                label(text(R.string.excluded_faces), true)
                excluded.forEach { face ->
                    button(activity.getString(R.string.restore_excluded_face, face.mediaId.takeLast(16))) {
                        mutate { repository.clear(face.anchorId) }
                    }.setOnLongClickListener {
                        activity.startActivity(Intent(activity, PhotoViewerActivity::class.java)
                            .putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, face.mediaId))
                        true
                    }
                }
            }
        }
    }

    private fun person() {
        val selectedPerson = personId ?: run { screen="people"; render(); return }
        root(text(R.string.person_details)); button(text(R.string.back)) { screen="people"; render() }
        val state=ModelCatalog.get(activity).snapshot();val generation=state.activeGenerations[AiFeature.PEOPLE] ?: run { label(text(R.string.people_not_ready)); return }
        val people=PeopleRepository(MediaDatabase.get(activity))
        load({ people.groups(generation).firstOrNull{it.personId==selectedPerson} to people.mergedSources(selectedPerson) }) { (group, mergedSources) ->
            if (ModelCatalog.get(activity).snapshot().activeGenerations[AiFeature.PEOPLE] != generation) {
                render()
                return@load
            }
            if(group==null){label(text(R.string.people_empty));return@load}
            label(group.name ?: text(R.string.unnamed_person),true)
            button(text(R.string.rename_person)){prompt(R.string.rename_person,group.name.orEmpty()){name->mutate{people.name(selectedPerson,name)}}}
            button(text(R.string.merge_person)){choosePerson(generation,selectedPerson,R.string.merge_person){target->screen="people";mutate{people.merge(selectedPerson,target)}}}
            if (mergedSources.isNotEmpty()) {
                label(text(R.string.merged_people), true)
                mergedSources.forEach { source -> button(activity.getString(R.string.undo_person_merge_named, source.name ?: text(R.string.unnamed_person))) {
                    mutate { people.unmerge(source.personId) }
                } }
            }
            group.faces.forEach { face ->
                val row=button("${face.mediaId.takeLast(16)} · ${face.anchorId.take(8)}") { activity.startActivity(Intent(activity,PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID,face.mediaId)) }
                row.setOnLongClickListener {
                    AlertDialog.Builder(activity).setTitle(R.string.correct_person).setItems(arrayOf(text(R.string.move_face),text(R.string.split_face),text(R.string.exclude_face),text(R.string.clear_person_correction))){_,which->when(which){
                        0->choosePerson(generation,selectedPerson,R.string.move_face){target->mutate{people.move(face.anchorId,target)}}
                        1->prompt(R.string.split_face){name->mutate{people.split(setOf(face.anchorId),selectedPerson,name)}}
                        2->mutate{people.exclude(face.anchorId)}
                        else->mutate{people.clear(face.anchorId)}
                    }}.show();true
                }
            }
        }
    }

    private fun choosePerson(generation: String, excludedPerson: String, title: Int, action: (String) -> Unit) {
        val people = PeopleRepository(MediaDatabase.get(activity))
        load({ people.groups(generation).filterNot { it.personId == excludedPerson } }) { groups ->
            if (ModelCatalog.get(activity).snapshot().activeGenerations[AiFeature.PEOPLE] != generation) {
                render()
                return@load
            }
            if (groups.isEmpty()) {
                Toast.makeText(activity, R.string.people_empty, Toast.LENGTH_SHORT).show()
                return@load
            }
            val labels = groups.map { group ->
                activity.resources.getQuantityString(
                    R.plurals.person_group_count,
                    group.faces.size,
                    group.name ?: text(R.string.unnamed_person),
                    group.faces.size,
                )
            }
            AlertDialog.Builder(activity).setTitle(title).setItems(labels.toTypedArray()) { _, which ->
                action(groups[which].personId)
            }.show()
        }
    }

    private fun semanticResults() {
        root(text(R.string.semantic_search_title))
        button(text(R.string.back)) { screen = "search"; render() }
        load({
            val result = io.github.mesteriis.lik.ai.SemanticSearchRepository(activity).search(semanticText)
            result to result.hits.mapNotNull { hit -> MediaDatabase.get(activity).media().get(hit.mediaId)?.let { hit to it } }
        }) { (result, rows) ->
            label(activity.getString(R.string.ai_index_coverage, result.indexed, result.available))
            if (!result.complete) label(text(R.string.semantic_results_incomplete))
            if (rows.isEmpty()) label(text(R.string.search_empty))
            rows.forEach { (hit, row) ->
                button("${row.displayName ?: row.mediaId} · ${String.format(java.util.Locale.getDefault(), "%.3f", hit.score)}") {
                    activity.startActivity(Intent(activity, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, row.mediaId))
                }
            }
        }
    }

    private fun open(value: CatalogSearch, title: String) { query = value; resultTitle = title; offset = 0; screen = "results"; render() }

    private fun results() {
        root(resultTitle)
        button(text(R.string.back)) { screen = if (section == GallerySection.ALBUMS) "albums" else "search"; render() }
        label(text(R.string.results_help))
        load({ repository.dao.search(query.query(61, offset)).map { it to repository.dao.tags(it.mediaId) } }) { rows ->
            if (query.ocrText.isNotBlank() && ModelCatalog.get(activity).snapshot().activeGenerations[AiFeature.OCR] != query.ocrGenerationId) {
                label(text(R.string.ocr_results_changed)); return@load
            }
            if (rows.isEmpty()) label(text(R.string.search_empty))
            rows.take(60).forEach { (row, tags) ->
                val date = (row.takenAt ?: row.addedAt)?.let {
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(it).atZone(libraryZone(activity)))
                } ?: text(R.string.timeline_undated)
                val title = row.displayName ?: row.privateFileId ?: row.mediaId
                val details = "${text(if (row.source == MediaSource.DEVICE) R.string.source_device else R.string.source_google)} · $date · ${row.mediaId.takeLast(8)}"
                val tagText = tags.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label }?.let { "\n$it" }.orEmpty()
                val view = button("${if (row.mediaId in selected()) "✓ " else ""}$title\n$details$tagText") {
                    if (selected().isNotEmpty()) { toggle(row.mediaId); render() }
                    else activity.startActivity(Intent(activity, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_PHOTO_ID, row.mediaId))
                }
                view.setOnLongClickListener { toggle(row.mediaId); render(); true }
            }
            if (offset > 0) button(text(R.string.previous_page)) { offset = (offset - 60).coerceAtLeast(0); render() }
            if (rows.size > 60) button(text(R.string.next_page)) { offset += 60; render() }
        }
    }

    fun actions(ids: Set<String>) {
        if (ids.isEmpty()) return
        val labels = mutableListOf(R.string.add_favorites, R.string.remove_favorites, R.string.add_to_album, R.string.add_tag, R.string.remove_tag)
        if (query.albumId != null && screen == "results") labels += R.string.remove_from_album
        AlertDialog.Builder(activity).setTitle(R.string.organize_selected).setItems(labels.map(::text).toTypedArray()) { _, which ->
            when (labels[which]) {
                R.string.add_favorites -> mutate { repository.organize(ids) { dao, id -> dao.favorite(Favorite(id)) } }
                R.string.remove_favorites -> mutate { repository.organize(ids) { dao, id -> dao.unfavorite(id) } }
                R.string.add_to_album -> load({ repository.dao.albums() }) { albums ->
                    val choices = albums.map { "${it.name} · ${it.albumId.take(8)}" } + text(R.string.create_album)
                    AlertDialog.Builder(activity).setTitle(R.string.add_to_album).setItems(choices.toTypedArray()) { _, index ->
                        if (index == albums.size) prompt(R.string.create_album) { name -> mutate {
                            val album = repository.createAlbum(name)
                            repository.organize(ids) { dao, id -> dao.addMember(AlbumMedia(album, id)) }
                        } } else mutate { repository.organize(ids) { dao, id -> dao.addMember(AlbumMedia(albums[index].albumId, id)) } }
                    }.show()
                }
                R.string.add_tag -> prompt(R.string.add_tag) { label -> mutate { repository.tag(ids, label) } }
                R.string.remove_tag -> prompt(R.string.remove_tag) { label -> mutate { repository.tag(ids, label, false) } }
                R.string.remove_from_album -> query.albumId?.let { album -> mutate { repository.organize(ids) { dao, id -> dao.removeMember(album, id) } } }
            }
        }.show()
    }

    private fun prompt(title: Int, initial: String = "", action: (String) -> Unit) {
        val input = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT; setText(initial); hint = text(title) }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(input).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isBlank()) input.error = text(R.string.name_required)
            else { action(value); dialog.dismiss() }
        } }
        dialog.show()
    }

    private fun mutate(work: () -> Any) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                val message = if (result is Int) activity.getString(R.string.organization_updated, result) else text(R.string.organization_saved)
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                if (active && container.isShown) render()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(activity, R.string.organization_error, Toast.LENGTH_LONG).show() }
        }
    }
}
