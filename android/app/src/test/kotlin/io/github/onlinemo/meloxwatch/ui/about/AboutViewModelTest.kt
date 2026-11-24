package io.github.onlinemo.meloxwatch.ui.about

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesField
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesRecovery
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesRecoveryReason
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.model.HelpPreferenceLimits
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 使用实际页面/选择器代次，偏好CAS seam只控制时序；真实文件另由存储回归核验。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = MeloXApplication::class, manifest = Config.NONE)
class AboutViewModelTest {
    private val models = ArrayList<AboutViewModel>()

    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun retireModels() {
        models.forEach { it.setVisible(false) }
        Dispatchers.resetMain()
    }

    @Test(timeout = 15_000L)
    fun aRetiredPickerDrainsItsOwnResultBeforeAnotherRequestCanUseTheCallbackSlot() = runBlocking {
        val model = model()
        diagnostics(model)
        model.requestExport()
        val old = checkNotNull(model.state.value.pickerRequest)
        assertTrue(model.pickerLaunched(old))
        model.show(HelpPage.OVERVIEW)
        model.show(HelpPage.DIAGNOSTICS)
        model.requestExport()
        assertNull(model.state.value.pickerRequest)
        assertTrue(model.state.value.waitingForPicker)
        model.destinationReturned(old, Uri.parse("content://fixture.invalid/tree/retired"), 0)
        assertFalse(model.state.value.waitingForPicker)
        assertFalse(model.state.value.working)
        model.requestExport()
        val next = checkNotNull(model.state.value.pickerRequest)
        assertTrue(old != next)
        assertTrue(model.pickerLaunched(next))
        model.destinationReturned(old, Uri.parse("content://fixture.invalid/tree/late"), 0)
        assertTrue(model.state.value.waitingForPicker)
        model.destinationReturned(next, null, 0)
        assertFalse(model.state.value.waitingForPicker)
        assertEquals(HelpFeedback.CANCELLED, model.state.value.feedback)
    }

    @Test(timeout = 15_000L)
    fun recreationRetainsAnActualLaunchWithoutOpeningThePickerAgain() = runBlocking {
        val saved = SavedStateHandle()
        val preferences = Store()
        val first = model(preferences, saved)
        diagnostics(first)
        first.requestExport()
        val token = checkNotNull(first.state.value.pickerRequest)
        assertTrue(first.pickerLaunched(token))
        first.setVisible(false)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val next = model(preferences, restored)
        next.setVisible(true)
        assertNull(next.state.value.pickerRequest)
        assertTrue(next.state.value.waitingForPicker)
        next.requestExport()
        assertNull(next.state.value.pickerRequest)
        next.destinationReturned(token, null, 0)
        assertEquals(HelpFeedback.CANCELLED, next.state.value.feedback)
        assertFalse(next.state.value.waitingForPicker)
        next.requestExport()
        assertNotNull(next.state.value.pickerRequest)
    }

    @Test(timeout = 15_000L)
    fun aFailedPickerLaunchReleasesOnlyItsOwnRequestAndAllowsAnExplicitRetry() = runBlocking {
        val model = model()
        diagnostics(model)
        model.requestExport()
        val token = checkNotNull(model.state.value.pickerRequest)
        assertTrue(model.pickerLaunched(token))
        model.pickerUnavailable(token)
        assertEquals(HelpFeedback.NO_FOLDER_PICKER, model.state.value.feedback)
        assertFalse(model.state.value.waitingForPicker)
        model.requestExport()
        val next = checkNotNull(model.state.value.pickerRequest)
        assertTrue(model.pickerLaunched(next))
        model.pickerUnavailable(token)
        assertTrue(model.state.value.waitingForPicker)
        model.destinationReturned(next, null, 0)
    }

    @Test(timeout = 15_000L)
    fun returningFromPrivacyNeverAcceptsANoticeAndRecoveryCannotBeImplicitlyConfirmed() {
        for (writable in listOf(true, false)) {
            val recovery = PreferencesRecovery(PreferencesRecoveryReason.INVALID_FIELDS, setOf(PreferencesField.SCREEN_SHAPE))
            val preferences = Store(PreferencesState.Ready(UserPreferences(), recovery, writable))
            val model = model(preferences)
            model.show(HelpPage.PRIVACY)
            model.setVisible(true)
            assertFalse(model.state.value.canWriteSettings)
            model.recordNotice(PrivacyNoticeChoice.READ)
            model.setTips(false)
            model.dismissGuide()
            model.show(HelpPage.OVERVIEW)
            assertEquals(0, preferences.writes)
            assertEquals(PrivacyNoticeChoice.UNANSWERED, model.state.value.help?.privacyNoticeChoice)
            model.setVisible(false)
        }
    }

    @Test(timeout = 15_000L)
    fun explicitReadingAndGuideProgressAreKeptWhenReturningOrRewatching() {
        val preferences = Store()
        val model = model(preferences)
        model.setVisible(true)
        model.beginGuide()
        model.guideStep(GuideStep.PLAYBACK)
        assertEquals(2, model.state.value.help?.onboardingStep)
        model.show(HelpPage.PRIVACY)
        model.recordNotice(PrivacyNoticeChoice.LATER)
        assertEquals(HelpPreferenceLimits.PRIVACY_NOTICE_VERSION, model.state.value.help?.privacyNoticeVersion)
        assertEquals(PrivacyNoticeChoice.LATER, model.state.value.help?.privacyNoticeChoice)
        val writes = preferences.writes
        model.show(HelpPage.OVERVIEW)
        model.beginGuide(restart = true)
        assertEquals(GuideStep.LOCAL_MUSIC, model.state.value.guideStep)
        assertEquals(2, model.state.value.help?.onboardingStep)
        assertEquals(writes, preferences.writes)
        model.show(HelpPage.OVERVIEW)
        model.beginGuide()
        assertEquals(GuideStep.PLAYBACK, model.state.value.guideStep)
    }

    @Test(timeout = 15_000L)
    fun cancellationAfterCommitKeepsTheStoredChoiceAndRejectsSuccessForTheRetiredPage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preferences = Store().apply { beforeCommit = {
            entered.complete(Unit)
            release.await()
        } }
        val model = model(preferences)
        model.show(HelpPage.PRIVACY)
        model.setVisible(true)
        model.recordNotice(PrivacyNoticeChoice.READ)
        withTimeout(5_000L) { entered.await() }
        model.setVisible(false)
        release.complete(Unit)
        withTimeout(5_000L) { model.state.filter { !it.working }.first() }
        model.setVisible(true)
        assertEquals(PrivacyNoticeChoice.READ, model.state.value.help?.privacyNoticeChoice)
        assertNull(model.state.value.feedback)
        assertEquals(1, preferences.writes)
    }

    private fun model(store: Store = Store(), saved: SavedStateHandle = SavedStateHandle()): AboutViewModel =
        AboutViewModel(RuntimeEnvironment.getApplication(), saved, store).also(models::add)

    private suspend fun diagnostics(model: AboutViewModel) {
        model.show(HelpPage.DIAGNOSTICS)
        model.setVisible(true)
        withTimeout(5_000L) { model.state.filter { !it.reading }.first() }
    }

    private class Store(initial: PreferencesState = PreferencesState.Ready(UserPreferences())) : UserPreferencesRepository {
        override val state = MutableStateFlow(initial)
        var writes = 0
        var beforeCommit: suspend () -> Unit = {}

        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult {
            val result = withContext(NonCancellable) {
                writes++
                beforeCommit()
                val current = (state.value as PreferencesState.Ready).preferences
                if (current.revision != expectedRevision) return@withContext PreferencesWriteResult.Conflict(current)
                val changed = current.copy(revision = current.revision + 1, help = draft.help)
                state.value = PreferencesState.Ready(changed)
                PreferencesWriteResult.Saved(changed)
            }
            currentCoroutineContext().ensureActive()
            return result
        }

        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult =
            throw AssertionError("Help must not reset preferences")
        override fun retryRead() = Unit
    }
}
