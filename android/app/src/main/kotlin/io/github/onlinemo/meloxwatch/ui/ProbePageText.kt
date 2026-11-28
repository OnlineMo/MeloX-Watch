package io.github.onlinemo.meloxwatch.ui

import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateOf
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.playback.dsp.DspLimits
import io.github.onlinemo.meloxwatch.ui.library.LibraryUiState
import io.github.onlinemo.meloxwatch.ui.online.OnlineScreen
import io.github.onlinemo.meloxwatch.ui.online.OnlineUiState
import io.github.onlinemo.meloxwatch.ui.meting.MetingSettingsState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistUiState
import io.github.onlinemo.meloxwatch.ui.queue.QueueViewerState
import io.github.onlinemo.meloxwatch.ui.about.AboutUiState
import io.github.onlinemo.meloxwatch.data.about.ProjectUpdateState
import java.util.Collections

internal enum class DspNamedTextRole { CHOICE, RENAME, REPLACE, DELETE, INPUT }

/** Only current, clipped-visible controls contribute private names to the page font corpus. */
@MainThread
internal class DspVisibleFontText(private val visibleNow: () -> Boolean) : AutoCloseable {
    private data class Key(val presetId: String?, val role: DspNamedTextRole)
    private data class Entry(val token: Any, val text: String? = null)
    private val entries = LinkedHashMap<Key, Entry>()
    private val published = mutableStateOf(DspFontTextSnapshot.EMPTY)
    private var closed = false
    private var failed = false

    val snapshot: DspFontTextSnapshot
        get() = if (!closed && visibleNow()) published.value else DspFontTextSnapshot.EMPTY

    fun preset(presetId: String, role: DspNamedTextRole): Registration {
        require(role != DspNamedTextRole.INPUT)
        return Registration(presetId, role)
    }

    fun input(): Registration = Registration(null, DspNamedTextRole.INPUT)

    /** Registration starts after old DisposableEffects retire, so replacement never needs extra slots. */
    inner class Registration internal constructor(presetId: String?, role: DspNamedTextRole) : AutoCloseable {
        private val key = Key(presetId, role)
        private val token = Any()
        private var attached = false
        private var retired = false

        fun attach() {
            if (attached || retired || !usable()) return
            val ids = entries.keys.mapNotNull { it.presetId }.toSet()
            if (key.presetId != null && key.presetId !in ids && ids.size == DspLimits.MAX_PRESETS) {
                reject()
                return
            }
            // Replacing this exact control retires its old token before accepting the new one.
            entries[key] = Entry(token)
            attached = true
            publish()
        }

        fun present(text: String, displayed: Boolean) {
            if (!usable() || !attached || entries[key]?.token !== token) return
            if (displayed && text.length > DspLimits.MAX_NAME_CHARACTERS) {
                reject()
                return
            }
            val next = text.takeIf { displayed && it.isNotEmpty() }
            if (entries[key]?.text == next) return
            entries[key] = Entry(token, next)
            publish()
        }

        override fun close() {
            retired = true
            attached = false
            if (entries[key]?.token !== token) return
            entries.remove(key)
            publish()
        }
    }

    private fun usable(): Boolean {
        if (closed) return false
        if (!visibleNow()) { close(); return false }
        return !failed
    }

    private fun reject() {
        failed = true
        entries.replaceAll { _, entry -> entry.copy(text = null) }
        publish()
    }

    private fun publish() {
        val next = if (closed) DspFontTextSnapshot.EMPTY
            else if (failed) DspFontTextSnapshot(emptyList(), LyricsFontIssue.INVALID_INPUT)
            else {
                val names = LinkedHashMap<String, String>()
                var input: String? = null
                for ((key, entry) in entries) entry.text?.let { text ->
                    if (key.presetId == null) input = text else names[key.presetId] = text
                }
                val parts = names.values.toMutableList()
                input?.let(parts::add)
                DspFontTextSnapshot(Collections.unmodifiableList(parts.distinct().sorted()), null)
            }
        // Layout/position callbacks and duplicate occurrences do not create a new font identity.
        if (published.value != next) published.value = next
    }

    override fun close() {
        closed = true
        failed = false
        entries.clear()
        publish()
    }
}

internal data class DspFontTextSnapshot(
    val parts: List<String>,
    val issue: LyricsFontIssue?,
) {
    companion object {
        val EMPTY = DspFontTextSnapshot(emptyList(), null)
    }
}

/** Font coverage uses bounded current-page text and visible keys, never a full music directory. */
internal fun probePageVisibleText(
    page: ProbePage,
    state: ProbeUiState,
    library: LibraryUiState,
    queue: QueueViewerState,
    playlists: PlaylistUiState,
    entry: PlaylistEntryUiState,
    visibleKeys: List<Any>,
    online: OnlineUiState? = null,
    meting: MetingSettingsState? = null,
    about: AboutUiState? = null,
    dspText: DspFontTextSnapshot? = null,
): List<String> = buildList {
    val keys = visibleKeys.toSet()
    if (page == ProbePage.DSP && "dsp:settings" in keys && dspText != null) addAll(dspText.parts)
    if (page == ProbePage.PLAYBACK) {
        state.playback.title?.let(::add)
        state.playback.artist?.let(::add)
        entry.playingTrack?.let { track ->
            add(track.title)
            track.metadata.artist?.let(::add)
        }
        state.selectedAudio?.displayName?.let(::add)
    }
    if (page == ProbePage.QUEUE) queue.entries.forEach { row ->
        if ("queue:entry:${row.queueEntryId}" in keys) row.displayName?.let(::add)
    }
    if (page == ProbePage.ONLINE && online != null) {
        when (online.screen) {
            OnlineScreen.HOME -> if ("online:account" in keys) online.account.account?.displayName?.let(::add)
            OnlineScreen.SEARCH -> {
                if ("online:search" in keys) add(online.searchDraft)
                online.searchHistory?.queries?.forEachIndexed { index, value ->
                    if ("online:history:$index" in keys) add(value)
                }
            }
            OnlineScreen.CATALOG -> {
                if ("online:title" in keys) online.title?.let(::add)
                val snapshotId = online.snapshot?.id
                if (snapshotId != null) online.rows.forEach { row ->
                    if ("online:item:$snapshotId:${row.order}" in keys) {
                        row.title?.let(::add)
                        row.artist?.let(::add)
                    }
                }
            }
            OnlineScreen.TRACK_ACTION -> if ("online:selection" in keys) online.selected?.let { row ->
                row.title?.let(::add)
                row.artist?.let(::add)
            }
            else -> Unit
        }
    }
    if (page == ProbePage.METING && meting != null && "meting:settings" in keys) {
        addAll(metingVisibleFontText(meting))
    }
    if (page == ProbePage.HELP && about != null) {
        if ("help:version" in keys) add(about.version.name)
        about.licenses.forEach { license ->
            if ("help:license:${license.id}" in keys ||
                (license.id == about.licenseId && "help:license-name" in keys)) {
                add(license.name)
                add(license.version)
                add(license.license)
            }
        }
        val update = about.updates as? ProjectUpdateState.Checked
        if (update != null) {
            if ("help:update-result" in keys) add(update.release.version.name)
            if ("help:update-notes" in keys) add(update.release.notes)
        }
        about.diagnostics.asReversed().forEachIndexed { index, event ->
            if ("help:diag:$index" in keys) {
                add(event.area.name)
                add(event.code.name)
                add("UTC")
            }
        }
        // 法律原文由页面的系统字体呈现，不把整份许可喂给主题字体覆盖检查。
    }
    if (page == ProbePage.LIBRARY) {
        val tracks = library.page?.tracks.orEmpty()
        for ((index, track) in tracks.withIndex()) {
            val gridKey = "pair:${tracks[index - index % 2].trackKey.trackId}"
            if ("track:${track.trackKey.providerId}:${track.trackKey.trackId}" in keys || gridKey in keys) {
                add(track.title)
                track.metadata.artist?.let(::add)
                track.metadata.album?.let(::add)
            }
        }
        library.groupPage?.groups.orEmpty().forEach { group ->
            if ("group:${group.key}" in keys) {
                group.title?.let(::add)
                group.subtitle?.let(::add)
            }
        }
        library.folderPage?.folders.orEmpty().forEach { folder ->
            if ("folder:${folder.nodeId}" in keys) folder.name?.let(::add)
        }
        library.detail?.let { track ->
            add(track.title)
            add(track.filename)
            track.metadata.artist?.let(::add)
            addAll(track.metadata.artists)
            track.metadata.album?.let(::add)
            track.metadata.albumArtist?.let(::add)
            track.metadata.mimeType?.let(::add)
        }
        if ("library:navigation" in keys) add(library.searchDraft)
    }
    if (page == ProbePage.PLAYLISTS) {
        playlists.catalogPage?.playlists.orEmpty().forEach { row ->
            if ("playlist:${row.playlistId}" in keys) add(row.name)
        }
        playlists.rows.forEach { row ->
            if ("member:${row.member.memberId}" in keys) row.title?.let(::add)
        }
        playlists.selected?.title?.let(::add)
        playlists.view?.snapshot?.name?.let(::add)
        playlists.nameDraft?.name?.let(::add)
        playlists.playDraft?.title?.let(::add)
        playlists.addLabel?.let(::add)
    }
}
