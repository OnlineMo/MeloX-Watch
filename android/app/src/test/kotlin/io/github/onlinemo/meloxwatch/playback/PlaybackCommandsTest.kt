package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 真正的 Android Bundle 协议边界；失败必须拒绝，不能借默认读取变成合法命令。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaybackCommandsTest {
    @Test
    fun notificationIntentExplicitlyTargetsPlaybackAndAllowsActivityReuse() {
        val context: Context = RuntimeEnvironment.getApplication()
        val intent = PlaybackCommands.playbackActivityIntent(context)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("io.github.onlinemo.meloxwatch.OPEN_PLAYBACK", intent.action)
        assertEquals("playback", intent.getStringExtra("destination"))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags)
        assertTrue(PlaybackCommands.opensPlayback(intent))

        val development = object : ContextWrapper(context) {
            override fun getPackageName(): String = "io.github.onlinemo.meloxwatch.dev"
        }
        val developmentIntent = PlaybackCommands.playbackActivityIntent(development)
        assertEquals(development.packageName, developmentIntent.component?.packageName)
        assertEquals(MainActivity::class.java.name, developmentIntent.component?.className)
        assertTrue(PlaybackCommands.opensPlayback(developmentIntent))
    }

    @Test
    fun ordinaryLaunchesAndMalformedDestinationsCannotBecomePlaybackNavigation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val valid = PlaybackCommands.playbackActivityIntent(context)
        val mutations: List<Pair<String, Intent.() -> Unit>> = listOf(
            "ordinary launch" to { action = Intent.ACTION_MAIN },
            "external view" to { action = Intent.ACTION_VIEW },
            "external send" to { action = Intent.ACTION_SEND },
            "missing action" to { action = null },
            "missing destination" to { removeExtra("destination") },
            "another destination" to { putExtra("destination", "library") },
            "Boolean destination" to { putExtra("destination", false) },
            "byte array destination" to { putExtra("destination", byteArrayOf(1)) },
            "null destination" to { putExtra("destination", null as String?) },
        )
        for ((label, mutate) in mutations) {
            assertFalse(label, PlaybackCommands.opensPlayback(Intent(valid).apply(mutate)))
        }
        assertFalse(PlaybackCommands.opensPlayback(null))
        val unreadable = object : Intent(valid) {
            override fun getExtras(): Bundle? = throw BadParcelableException("Unreadable destination")
        }
        assertFalse(PlaybackCommands.opensPlayback(unreadable))
    }

    @Test
    fun wrongPrimitiveTypesCannotTurnIntoValidDefaultValues() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "String schema" to { putString("schema", "1") },
            "Int queue revision" to { putInt("revision", 0) },
            "Long flags" to { putLong("flags", 1L) },
            "Int known duration" to { putInt("duration", 0) },
            "Boolean generation" to { putBoolean("generation", false) },
            "byte array track identity" to { putByteArray("track", byteArrayOf(1, 2)) },
            "Int title" to { putInt("title", 0) },
            "Int URI" to { putInt("uri", 0) },
        )
        for ((label, mutate) in mutations) {
            val arguments = validLocalArguments()
            arguments.mutate()
            rejects(label) { PlaybackCommands.readLocal(arguments) }
        }
        val selection = PlaybackCommands.selectionArguments(base(), "instance-other")
        selection.putInt("revision", 0)
        rejects("Int selection revision") { PlaybackCommands.readSelection(selection) }
    }

    @Test
    fun missingOrUnknownSchemaRejectsLocalAndSelectionCommands() {
        for (schema in listOf<Int?>(null, 0, 2)) {
            val local = validLocalArguments()
            val selection = PlaybackCommands.selectionArguments(base(), "instance-other")
            if (schema == null) {
                local.remove("schema")
                selection.remove("schema")
            } else {
                local.putInt("schema", schema)
                selection.putInt("schema", schema)
            }
            rejects("local schema $schema") { PlaybackCommands.readLocal(local) }
            rejects("selection schema $schema") { PlaybackCommands.readSelection(selection) }
        }
    }

    @Test
    fun aMissingOrNegativeRevisionNeverBecomesAnUnstartedQueue() {
        for (revision in listOf<Long?>(null, -1L)) {
            val local = validLocalArguments()
            val selection = PlaybackCommands.selectionArguments(base(), "instance-other")
            if (revision == null) {
                local.remove("revision")
                selection.remove("revision")
            } else {
                local.putLong("revision", revision)
                selection.putLong("revision", revision)
            }
            rejects("local revision $revision") { PlaybackCommands.readLocal(local) }
            rejects("selection revision $revision") { PlaybackCommands.readSelection(selection) }
        }
    }

    @Test
    fun rawTitleBeyondTheProtocolBudgetIsRejectedAndTheBoundaryIsAccepted() = runBlocking {
        val oversized = validLocalArguments().apply { putString("title", "曲".repeat(2049)) }
        rejects("title beyond protocol budget") { PlaybackCommands.readLocal(oversized) }

        val title = "曲".repeat(2048)
        val accepted = PlaybackCommands.readLocal(validLocalArguments().apply { putString("title", title) })
        val page = accepted.source.read(0L, 1)
        assertEquals(title, page.entries.single().displayName)
        assertEquals(accepted.request.view, page.view)
    }

    @Test
    fun fullMetadataTitleProducesABoundedPlayableSummaryWithoutChangingTheSource() = runBlocking {
        val title = "曲".repeat(4096)
        val metadata = LocalAudioMetadata(title = title, durationMs = 180_000L)
        val input = entry().copy(displayName = metadata.title, durationMs = metadata.durationMs)
        val arguments = PlaybackCommands.localArguments(input, base(), "operation-long-title")
        val command = PlaybackCommands.readLocal(arguments)
        val page = command.source.read(0L, 1)
        val summary = "曲".repeat(2048)

        assertEquals(title, metadata.title)
        assertEquals(title, input.displayName)
        assertEquals(summary, arguments.getString("title"))
        assertEquals(input.copy(originalOrder = 0L, playbackOrder = 0L, displayName = summary),
            page.entries.single())
        assertEquals(command.request.view, page.view)
    }

    @Test
    fun titleSummariesKeepWholeSurrogatePairsAndCombiningSequences() = runBlocking {
        val cases = listOf(
            "曲".repeat(2047) + "😀尾" to "曲".repeat(2047),
            "曲".repeat(2046) + "😀尾" to ("曲".repeat(2046) + "😀"),
            "曲".repeat(2047) + "e\u0301尾" to "曲".repeat(2047),
            "曲".repeat(2046) + "e\u0301尾" to ("曲".repeat(2046) + "e\u0301"),
        )
        for ((title, expected) in cases) {
            val input = entry().copy(displayName = title)
            val decoded = PlaybackCommands.readLocal(
                PlaybackCommands.localArguments(input, base(), "operation-cluster"),
            ).source.read(0L, 1).entries.single()
            assertEquals(input.copy(originalOrder = 0L, playbackOrder = 0L, displayName = expected), decoded)
            assertEquals(title, input.displayName)
        }
    }

    @Test
    fun aSingleOverBudgetCharacterClusterRemainsPlayableWithAnUnknownTitle() = runBlocking {
        val title = "e" + "\u0301".repeat(2048) + "尾"
        val metadata = LocalAudioMetadata(title = title)
        val input = entry().copy(displayName = metadata.title)
        val arguments = PlaybackCommands.localArguments(input, base(), "operation-unknown-title")
        val decoded = PlaybackCommands.readLocal(arguments).source.read(0L, 1).entries.single()
        assertNull(arguments.getString("title"))
        assertEquals(input.copy(originalOrder = 0L, playbackOrder = 0L, displayName = null), decoded)
        assertEquals(title, metadata.title)
        assertEquals(title, input.displayName)
    }

    @Test
    fun missingFileAndNetworkLocationsCannotBecomeALocalSource() {
        val origins = listOf<String?>(
            null,
            "file:///music/example.mp3",
            "https://example.invalid/music/example.mp3",
            "content:/music/example.mp3",
            "content:///music/example.mp3",
        )
        for (origin in origins) {
            for (key in listOf("uri", "grant_uri")) {
                val arguments = validLocalArguments()
                if (origin == null) arguments.remove(key) else arguments.putString(key, origin)
                rejects("$key with origin $origin") { PlaybackCommands.readLocal(arguments) }
            }
        }
    }

    @Test
    fun malformedStableIdentifiersAndAccessMetadataAreRejected() {
        val mutations: List<Pair<String, Bundle.() -> Unit>> = listOf(
            "blank operation" to { putString("operation", " ") },
            "blank queue entry" to { putString("entry", "") },
            "oversized track identity" to { putString("track", "a".repeat(129)) },
            "missing location identity" to { remove("location") },
            "unknown access enum" to { putString("access", "REGRANTED") },
            "negative known duration" to { putLong("duration", -1L) },
        )
        for ((label, mutate) in mutations) {
            val arguments = validLocalArguments()
            arguments.mutate()
            rejects(label) { PlaybackCommands.readLocal(arguments) }
        }
        for (key in listOf("generation", "entry")) {
            val arguments = PlaybackCommands.selectionArguments(base(), "instance-other")
            arguments.putString(key, "")
            rejects("blank selection $key") { PlaybackCommands.readSelection(arguments) }
        }
    }

    @Test
    fun repeatedTracksKeepIndependentInstancesAndLocationAliases() = runBlocking {
        val track = TrackKey(TrackKey.LOCAL_PROVIDER, "stable-track")
        val first = entry("instance-first", track, "location-first", "content://audio/first")
        val second = entry("instance-second", track, "location-second", "content://audio/alias")
        val unrelated = entry("instance-unrelated", TrackKey(TrackKey.LOCAL_PROVIDER, "another-track"),
            "location-unrelated", "content://audio/first")
        val decoded = listOf(first, second, unrelated).mapIndexed { index, input ->
            PlaybackCommands.readLocal(PlaybackCommands.localArguments(input, base(), "operation-$index"))
                .source.read(0L, 1).entries.single()
        }
        assertEquals(first.trackKey, decoded[0].trackKey)
        assertEquals(decoded[0].trackKey, decoded[1].trackKey)
        assertNotEquals(decoded[0].queueEntryId, decoded[1].queueEntryId)
        assertNotEquals(decoded[0].location?.uri, decoded[1].location?.uri)
        assertEquals(decoded[0].location?.uri, decoded[2].location?.uri)
        assertNotEquals(decoded[0].trackKey, decoded[2].trackKey)
        assertEquals(listOf(first.queueEntryId, second.queueEntryId, unrelated.queueEntryId), decoded.map { it.queueEntryId })
    }

    @Test
    fun aDecodedSingleItemIsFrozenBeforeCallerBundleMutation() = runBlocking {
        val input = entry()
        val arguments = PlaybackCommands.localArguments(input, base(), "operation-frozen")
        val command = PlaybackCommands.readLocal(arguments)
        arguments.putString("operation", "operation-replaced")
        arguments.putString("track", "identity-replaced")
        arguments.putString("uri", "content://audio/replaced")
        arguments.putString("title", "title-replaced")
        arguments.putLong("revision", 0L)

        val first = command.source.read(0L, 1)
        val replay = command.source.read(0L, 1)
        assertEquals(first, replay)
        assertEquals(FrozenQueueView("single:operation-frozen", 0L, 1L), first.view)
        assertEquals(command.request.view, first.view)
        assertEquals("operation-frozen", command.request.operationId)
        assertEquals(base().generationId, command.request.expectedGenerationId)
        assertEquals(base().queueRevision, command.request.expectedQueueRevision)
        assertFalse(command.request.allowEmpty)
        val frozen = first.entries.single()
        assertEquals(input.trackKey, frozen.trackKey)
        assertEquals(input.queueEntryId, frozen.queueEntryId)
        assertEquals(input.location, frozen.location)
        assertEquals(input.displayName, frozen.displayName)
        assertNull(frozen.durationMs)
        assertEquals(0L, frozen.originalOrder)
        assertEquals(0L, frozen.playbackOrder)
        suspendRejects("another offset in a single-item view") { command.source.read(1L, 1) }
        suspendRejects("a different page budget in a single-item view") { command.source.read(0L, 2) }
    }

    @Test
    fun selectionBindsTheSpecifiedGenerationQueueRevisionAndInstance() {
        val previous = base()
        val laterProgress = previous.copy(checkpointRevision = 1000L,
            checkpoint = QueueCheckpoint(previous.currentEntryId, 6000L))
        val first = PlaybackCommands.readSelection(PlaybackCommands.selectionArguments(previous, "instance-other"))
        val afterProgress = PlaybackCommands.readSelection(PlaybackCommands.selectionArguments(laterProgress, "instance-other"))
        assertEquals(QueueSelectionCommand("generation-existing", 42L, "instance-other"), first)
        assertEquals(first, afterProgress)
        assertNotEquals(previous.currentEntryId, first.entryId)
    }

    @Test
    fun aLibraryCommandCarriesOnlyAnAdoptableLeaseAndStableSelection() {
        val selected = TrackKey(TrackKey.LOCAL_PROVIDER, "selected-local")
        val arguments = PlaybackCommands.libraryArguments("view-library", selected, base(), "play-library")
        val decoded = PlaybackCommands.readLibrary(arguments)
        assertEquals(LibraryQueueCommand("view-library", "play-library", base().generationId, 42L, selected), decoded)
        assertFalse(arguments.containsKey("entries"))
        assertFalse(arguments.containsKey("uri"))
        assertFalse(arguments.containsKey("totalCount"))
        arguments.putString("view", "changed-view")
        arguments.putString("track", "changed-selection")
        assertEquals("view-library", decoded.viewId)
        assertEquals(selected, decoded.selected)
        val playAll = PlaybackCommands.readLibrary(PlaybackCommands.libraryArguments("all-view", null, null, "new-library"))
        assertNull(playAll.selected)
        assertNull(playAll.expectedGenerationId)
        assertEquals(0L, playAll.expectedQueueRevision)
    }

    @Test
    fun malformedLibraryLeasesAndPrimitiveTypesRejectBeforeQueueMutation() {
        val mutations: List<Bundle.() -> Unit> = listOf(
            { putInt("revision", 0) }, { putLong("revision", -1L) }, { remove("revision") },
            { putString("schema", "1") }, { putInt("schema", 2) }, { remove("schema") },
            { putString("view", "") }, { putString("view", "x".repeat(129)) }, { putByteArray("view", byteArrayOf(1)) },
            { putBoolean("generation", false) }, { putString("generation", " ") },
            { putString("track", "") }, { putLong("track", 1L) }, { putString("track", "x".repeat(129)) },
        )
        for (mutate in mutations) {
            val arguments = PlaybackCommands.libraryArguments("library-view", null, base(), "library-operation")
            arguments.mutate()
            rejects("invalid library lease/primitive") { PlaybackCommands.readLibrary(arguments) }
        }
        rejects("online selection through the local-library command") {
            PlaybackCommands.libraryArguments("library-view", TrackKey("online", "selected"), base(), "library-operation")
        }
    }

    private fun validLocalArguments(): Bundle = PlaybackCommands.localArguments(entry(), base(), "operation-valid")

    private fun entry(
        instanceId: String = "instance-valid",
        track: TrackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "stable-track"),
        locationId: String = "location-valid",
        uri: String = "content://audio/valid",
    ): QueueEntry = QueueEntry(
        instanceId,
        track,
        originalOrder = 12L,
        playbackOrder = 6L,
        displayName = "Local audio",
        location = LocalLocationReference(
            locationId,
            uri,
            grantUri = "content://audio/tree",
            grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            access = LocalAccess.PERSISTED,
        ),
    )

    private fun base() = QueueSnapshot(
        "generation-existing",
        queueRevision = 42L,
        checkpointRevision = 900L,
        view = FrozenQueueView("view-existing", 7L, 10001L),
        mode = QueueMode(),
        checkpoint = QueueCheckpoint("instance-current", 1234L),
        currentOriginalOrder = 9999L,
        currentPlaybackOrder = 9999L,
    )

    private fun rejects(label: String, read: () -> Unit) {
        try {
            read()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Protocol accepted $label")
    }

    private suspend fun suspendRejects(label: String, read: suspend () -> Unit) {
        try {
            read()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Frozen source accepted $label")
    }
}
