package io.github.onlinemo.meloxwatch.ui.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Public Android resolution and opaque ticket boundaries; no device support or original AC result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class SourcesEntryFlowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val uri: Uri get() = Uri.parse("content://sources.fixture/audio")

    @Test
    fun eachRealComponentCombinationIsObservedWithoutInferringReadPermission(): Unit {
        for (mask in 0..7) {
            removeSourcesPicker(context, SourcesPickerKind.FILE)
            removeSourcesPicker(context, SourcesPickerKind.TREE)
            if (mask and 1 != 0) installSourcesPicker(context, SourcesPickerKind.FILE)
            if (mask and 2 != 0) installSourcesPicker(context, SourcesPickerKind.TREE)
            installSourcesMediaPresence(context, enabled = mask and 4 != 0)
            val observed = sourcesEntryCapabilities(context)
            fun expected(bit: Int) = if (mask and bit != 0) SourcesEntryAvailability.AVAILABLE else SourcesEntryAvailability.UNAVAILABLE
            assertEquals(expected(1), observed.file)
            assertEquals(expected(2), observed.tree)
            assertEquals(expected(4), observed.media)
            assertNotNull(observed.checkedAtElapsedMs)
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    @Test
    fun freshQueriesObserveRemovedPickersAndDoNotExposeAnotherPackagesPrivateMediaProvider(): Unit {
        installSourcesPicker(context, SourcesPickerKind.FILE)
        assertEquals(SourcesEntryAvailability.AVAILABLE, sourcesEntryCapabilities(context).file)
        removeSourcesPicker(context, SourcesPickerKind.FILE)
        assertEquals(SourcesEntryAvailability.UNAVAILABLE, sourcesEntryCapabilities(context).file)
        installSourcesMediaPresence(context, exported = false, packageName = "sources.fixture.private")
        assertEquals(SourcesEntryAvailability.UNAVAILABLE, sourcesEntryCapabilities(context).media)
        installSourcesMediaPresence(context, exported = false)
        assertEquals(SourcesEntryAvailability.AVAILABLE, sourcesEntryCapabilities(context).media)
    }

    @Test
    fun aFailingPackageManagerGetterRemainsUnknownAndCancellationStillPropagates(): Unit {
        val unavailable = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = throw IllegalStateException("Fixture manager unavailable")
        }
        val result = sourcesEntryCapabilities(unavailable)
        assertEquals(SourcesEntryAvailability.UNKNOWN, result.file)
        assertEquals(SourcesEntryAvailability.UNKNOWN, result.tree)
        assertEquals(SourcesEntryAvailability.UNKNOWN, result.media)
        val cancelled = CancellationException("Fixture capability query cancelled")
        val retiring = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = throw cancelled
        }
        assertSame(cancelled, runCatching { sourcesEntryCapabilities(retiring) }.exceptionOrNull())
    }

    @Test
    fun aRetiredPlatformLaunchMustDrainBeforeAnyKindCanLaunchAndWrongTicketsCannotDrainIt(): Unit {
        val requests = SourcesPickerRequests(SavedStateHandle())
        val firstOwner = LibraryNavigationOwner()
        requests.selectPage(firstOwner)
        val first = requireNotNull(requests.begin(SourcesPickerKind.FILE))
        firstOwner.retire()
        requests.selectPage(LibraryNavigationOwner())
        for (kind in SourcesPickerKind.entries) assertNull(requests.begin(kind))
        val unrelated = SourcesPickerTicket(SourcesPickerKind.FILE)
        assertEquals(SourcesPickerOutcome.IGNORED, requests.documentResult(unrelated, Activity.RESULT_OK, uri, 0))
        assertTrue(requests.awaitingPlatform)
        assertEquals(SourcesPickerOutcome.IGNORED, requests.mediaResult(first, granted = true))
        assertTrue(requests.awaitingPlatform)
        assertEquals(SourcesPickerOutcome.EXPIRED, requests.documentResult(first, Activity.RESULT_OK, uri, 0))
        assertNull(requests.takeReturn())
        val next = requireNotNull(requests.begin(SourcesPickerKind.TREE))
        assertEquals(SourcesPickerOutcome.IGNORED, requests.documentResult(first, Activity.RESULT_OK, uri, 0))
        assertSame(next, requests.awaiting(SourcesPickerKind.TREE))
    }

    @Test
    fun legalReturnsAreConsumedOnceAndANewIntentRetiresBothBufferedAndTakenInput(): Unit {
        val saved = SavedStateHandle()
        val requests = SourcesPickerRequests(saved)
        val owner = LibraryNavigationOwner()
        requests.selectPage(owner)
        val first = requireNotNull(requests.begin(SourcesPickerKind.FILE))
        assertEquals(SourcesPickerOutcome.DEFERRED, requests.documentResult(first, Activity.RESULT_OK, uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertFalse(requests.selectPage(owner))
        assertTrue(requests.awaitingForeground)
        assertTrue(saved.keys().isEmpty())
        assertNull(requests.begin(SourcesPickerKind.MEDIA_ALL))
        val input = requireNotNull(requests.takeReturn())
        assertEquals(uri, input.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, input.flags)
        assertTrue(input.isCurrent())
        assertNull(requests.takeReturn())
        requests.newExternalIntent()
        assertFalse(input.isCurrent())
        val second = requireNotNull(requests.begin(SourcesPickerKind.FILE))
        assertEquals(SourcesPickerOutcome.DEFERRED, requests.documentResult(second, Activity.RESULT_OK, uri, 0))
        requests.newExternalIntent()
        assertNull(requests.takeReturn())
        assertFalse(requests.awaitingForeground)
    }

    @Test
    fun savedStateRestoresOnlyDrainAndNeverRestoresAnAcceptedUriOrReadRequest(): Unit {
        val saved = SavedStateHandle()
        val original = SourcesPickerRequests(saved)
        original.selectPage(LibraryNavigationOwner())
        val old = requireNotNull(original.begin(SourcesPickerKind.TREE))
        val persisted = saved.get<List<*>>(saved.keys().single())
        assertEquals(listOf(1, SourcesPickerKind.TREE.name, old.id), persisted)
        val restoredState = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val restored = SourcesPickerRequests(restoredState)
        restored.selectPage(LibraryNavigationOwner())
        val drain = requireNotNull(restored.awaiting(SourcesPickerKind.TREE))
        assertNotSame(old, drain)
        assertEquals(old.id, drain.id)
        assertTrue(restored.restoredDrain)
        assertNull(restored.begin(SourcesPickerKind.FILE))
        assertEquals(SourcesPickerOutcome.EXPIRED, restored.documentResult(drain, Activity.RESULT_OK, uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        assertNull(restored.takeReturn())
        assertTrue(restoredState.keys().isEmpty())
        assertNotNull(restored.begin(SourcesPickerKind.FILE))
    }

    @Test
    fun cancellationMalformedUrisAndDeniedPermissionNeverBecomeBufferedReadInputs(): Unit {
        val requests = SourcesPickerRequests(SavedStateHandle())
        requests.selectPage(LibraryNavigationOwner())
        val cancelled = requireNotNull(requests.begin(SourcesPickerKind.FILE))
        assertEquals(SourcesPickerOutcome.CANCELLED, requests.documentResult(cancelled, Activity.RESULT_CANCELED, uri, 0))
        val invalid = listOf(null, Uri.parse("file:///fixture.mp3"), Uri.parse("content:///audio"),
            Uri.parse("content://sources.fixture/" + "x".repeat(LocalInputLimits.URI_CHARACTERS)))
        for (value in invalid) {
            val ticket = requireNotNull(requests.begin(SourcesPickerKind.FILE))
            assertEquals(SourcesPickerOutcome.INVALID, requests.documentResult(ticket, Activity.RESULT_OK, value, 0))
            assertNull(requests.takeReturn())
        }
        val media = requireNotNull(requests.begin(SourcesPickerKind.MEDIA_ALL))
        assertEquals(SourcesPickerOutcome.PERMISSION_DENIED, requests.mediaResult(media, granted = false))
        assertNull(requests.takeReturn())
        assertFalse(requests.awaitingPlatform)
    }

    @Test
    fun anUncertainLaunchFailureKeepsDrainWhileADefiniteFailureAllowsAnExplicitNewLaunch(): Unit {
        val requests = SourcesPickerRequests(SavedStateHandle())
        requests.selectPage(LibraryNavigationOwner())
        val uncertain = requireNotNull(requests.begin(SourcesPickerKind.FILE))
        assertTrue(requests.launchFailed(uncertain, definitelyNotLaunched = false))
        assertNull(requests.begin(SourcesPickerKind.TREE))
        assertEquals(SourcesPickerOutcome.EXPIRED, requests.documentResult(uncertain, Activity.RESULT_OK, uri, 0))
        val definite = requireNotNull(requests.begin(SourcesPickerKind.TREE))
        assertTrue(requests.launchFailed(definite, definitelyNotLaunched = true))
        assertFalse(requests.awaitingPlatform)
        assertNotNull(requests.begin(SourcesPickerKind.FILE))
    }
}
