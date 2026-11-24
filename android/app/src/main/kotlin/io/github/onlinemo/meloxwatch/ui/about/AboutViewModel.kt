package io.github.onlinemo.meloxwatch.ui.about

import android.app.Application
import android.net.Uri
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.BuildConfig
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.about.AppLicenseEntry
import io.github.onlinemo.meloxwatch.data.about.AppVersion
import io.github.onlinemo.meloxwatch.data.about.DiagnosticArea
import io.github.onlinemo.meloxwatch.data.about.DiagnosticCode
import io.github.onlinemo.meloxwatch.data.about.DiagnosticSourceRetirementException
import io.github.onlinemo.meloxwatch.data.about.ProjectUpdateException
import io.github.onlinemo.meloxwatch.data.about.ProjectUpdateIssue
import io.github.onlinemo.meloxwatch.data.about.ProjectUpdateState
import io.github.onlinemo.meloxwatch.data.about.SafeDiagnosticEvent
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactDocumentDestination
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactException
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactIssue
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactSource
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.model.HelpPreferenceLimits
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HelpPage { OVERVIEW, CORE_HELP, PRIVACY, LICENSES, LICENSE_TEXT, UPDATE, GUIDE, DIAGNOSTICS, SUPPORT }
enum class GuideStep { LOCAL_MUSIC, PERMISSIONS, PLAYBACK, PRIVACY }
enum class HelpFeedback {
    SAVED, CONFLICT, SETTINGS_UNAVAILABLE, IO, CANCELLED, EXPORTED,
    LOCAL_FOLDER_REQUIRED, CLEANUP_REQUIRED, NO_FOLDER_PICKER, EXTERNAL_UNAVAILABLE, LICENSE_UNAVAILABLE,
}

data class AboutUiState(
    val page: HelpPage = HelpPage.OVERVIEW,
    val guideStep: GuideStep = GuideStep.LOCAL_MUSIC,
    val storage: PreferencesState = PreferencesState.Loading,
    val version: AppVersion = AppVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
    val working: Boolean = false,
    val reading: Boolean = false,
    val feedback: HelpFeedback? = null,
    val updates: ProjectUpdateState = ProjectUpdateState.Idle,
    val licenses: List<AppLicenseEntry> = emptyList(),
    val licenseId: String? = null,
    val licenseLines: List<String> = emptyList(),
    val diagnostics: List<SafeDiagnosticEvent> = emptyList(),
    val pickerRequest: String? = null,
    val waitingForPicker: Boolean = false,
) {
    val help: HelpPreferences? get() = (storage as? PreferencesState.Ready)?.preferences?.help
    val canWriteSettings: Boolean get() = (storage as? PreferencesState.Ready)?.let { it.canWrite && it.recovery == null } == true && !working
    val canExport: Boolean get() = !working && !waitingForPicker && pickerRequest == null
}

/** 页面离开取消任务；仅明确的选择器请求可跨其外部Activity等待，不重放导出或联网。 */
class AboutViewModel internal constructor(
    application: Application,
    private val saved: SavedStateHandle,
    private val preferences: UserPreferencesRepository,
) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) :
        this(application, saved, (application as MeloXApplication).preferences)

    private val app = application as MeloXApplication
    private val _state = MutableStateFlow(AboutUiState(
        page = HelpPage.entries.firstOrNull { it.name == saved.get<String>(KEY_PAGE) } ?: HelpPage.OVERVIEW,
        guideStep = GuideStep.entries.firstOrNull { it.name == saved.get<String>(KEY_GUIDE) } ?: GuideStep.LOCAL_MUSIC,
        storage = preferences.state.value,
        licenseId = saved.get<String>(KEY_LICENSE),
    ))
    val state = _state.asStateFlow()
    private var visible = false
    private var presentation = Any()
    private var storeJob: Job? = null
    private var readJob: Job? = null
    private var readTicket: Any? = null
    private var updateJob: Job? = null
    private var updateTicket: Any? = null
    private var workJob: Job? = null
    private var workTicket: Any? = null
    private var workPresentation: Any? = null
    private var pickerToken: String? = saved.get<String>(KEY_PICKER)?.takeIf(::uuid)
    private var pickerLaunched: Boolean = saved.get<Boolean>(KEY_PICKER_LAUNCHED) == true
    private var launchedPickerToken: String? = saved.get<String>(KEY_LAUNCH_TOKEN)?.takeIf(::uuid)
        ?: pickerToken?.takeIf { pickerLaunched }
    private var launchedPickerExpired: Boolean = saved.get<Boolean>(KEY_LAUNCH_EXPIRED) == true
    private var returnedDestination: LocalArtifactDocumentDestination? = null

    init {
        // 无法证明未提交请求是否曾启动时需要重新发起操作；重建不会再次打开系统选择器。
        pickerLaunched = launchedPickerToken != null
        if (!pickerLaunched || _state.value.page != HelpPage.DIAGNOSTICS || pickerToken != launchedPickerToken) retirePicker()
        else _state.update { it.copy(waitingForPicker = true) }
    }

    @MainThread
    fun setVisible(value: Boolean) {
        if (visible == value) return
        presentation = Any()
        visible = value
        if (!value) {
            storeJob?.cancel()
            storeJob = null
            cancelReads()
            cancelUpdate()
            workJob?.cancel()
            if (!pickerLaunched) retirePicker()
            _state.update { it.copy(licenseLines = emptyList()) }
            return
        }
        _state.update { it.copy(storage = preferences.state.value) }
        storeJob = viewModelScope.launch {
            preferences.state.collect { storage ->
                _state.update { it.copy(storage = storage) }
                if (!saved.contains(KEY_GUIDE) && storage is PreferencesState.Ready) {
                    val step = GuideStep.entries[storage.preferences.help.onboardingStep]
                    saved[KEY_GUIDE] = step.name
                    _state.update { it.copy(guideStep = step) }
                }
            }
        }
        returnedDestination?.let {
            returnedDestination = null
            exportTo(it)
        } ?: loadPage()
    }

    @MainThread
    fun show(page: HelpPage) {
        presentation = Any()
        cancelReads()
        cancelUpdate()
        if (page != _state.value.page) workJob?.cancel()
        if (page != HelpPage.DIAGNOSTICS) retirePicker()
        saved[KEY_PAGE] = page.name
        _state.update { it.copy(page = page, feedback = null, licenseLines = emptyList()) }
        if (visible) loadPage()
    }

    @MainThread
    fun showLicense(id: String) {
        if (_state.value.licenses.none { it.id == id }) return
        saved[KEY_LICENSE] = id
        _state.update { it.copy(licenseId = id) }
        show(HelpPage.LICENSE_TEXT)
    }

    @MainThread
    fun guideStep(step: GuideStep) {
        if (!visible || _state.value.page != HelpPage.GUIDE || _state.value.working) return
        saved[KEY_GUIDE] = step.name
        _state.update { it.copy(guideStep = step) }
        if (_state.value.canWriteSettings && step.ordinal > (_state.value.help?.onboardingStep ?: 0)) {
            // 已完成的说明步骤可跨进程恢复；重看或返回上一步不倒退完成记录。
            writeHelp({ it.copy(onboardingStep = step.ordinal) })
        }
    }

    @MainThread
    fun beginGuide(restart: Boolean = false) {
        val step = if (restart) GuideStep.LOCAL_MUSIC else
            GuideStep.entries[_state.value.help?.onboardingStep ?: 0]
        saved[KEY_GUIDE] = step.name
        _state.update { it.copy(guideStep = step) }
        show(HelpPage.GUIDE)
    }

    @MainThread
    fun finishGuide() = writeHelp({ it.copy(
        onboardingCompletedVersion = HelpPreferenceLimits.GUIDE_VERSION,
        onboardingStep = HelpPreferenceLimits.GUIDE_STEP_COUNT - 1,
    ) }) {
        show(HelpPage.OVERVIEW)
    }

    @MainThread
    fun dismissGuide() = writeHelp({ it.copy(onboardingDismissedVersion = HelpPreferenceLimits.GUIDE_VERSION) }) {
        show(HelpPage.OVERVIEW)
    }

    @MainThread
    fun setTips(value: Boolean) = writeHelp({ it.copy(showTips = value) })

    @MainThread
    fun recordNotice(choice: PrivacyNoticeChoice) {
        if (_state.value.page != HelpPage.PRIVACY || choice == PrivacyNoticeChoice.UNANSWERED) return
        writeHelp({ it.copy(privacyNoticeVersion = HelpPreferenceLimits.PRIVACY_NOTICE_VERSION, privacyNoticeChoice = choice) })
    }

    @MainThread
    fun retrySettings() = preferences.retryRead()

    @MainThread
    fun checkUpdates() {
        if (!visible || _state.value.page != HelpPage.UPDATE || updateJob != null) return
        val ticket = Any()
        updateTicket = ticket
        _state.update { it.copy(updates = ProjectUpdateState.Checking) }
        updateJob = viewModelScope.launch {
            try {
                val release = app.projectUpdates.check(_state.value.version)
                if (updateTicket === ticket && visible && _state.value.page == HelpPage.UPDATE) {
                    _state.update { it.copy(updates = ProjectUpdateState.Checked(release,
                        release.version != it.version, System.currentTimeMillis())) }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ProjectUpdateException) {
                if (updateTicket === ticket && visible) {
                    _state.update { it.copy(updates = ProjectUpdateState.Failed(failure.issue, System.currentTimeMillis())) }
                }
                app.recordDiagnostic(DiagnosticArea.UPDATE, when (failure.issue) {
                    ProjectUpdateIssue.TIMEOUT -> DiagnosticCode.TIMEOUT
                    ProjectUpdateIssue.BUSY -> DiagnosticCode.BUSY
                    ProjectUpdateIssue.INVALID_RELEASE, ProjectUpdateIssue.CERTIFICATE_MISMATCH,
                    ProjectUpdateIssue.DOWNGRADE, ProjectUpdateIssue.UNTRUSTED_SOURCE -> DiagnosticCode.INVALID_DATA
                    else -> DiagnosticCode.CONNECTION
                })
            } finally {
                if (updateTicket === ticket) {
                    updateTicket = null
                    updateJob = null
                }
            }
        }
    }

    @MainThread
    fun cancelUpdate() {
        val active = updateTicket != null
        updateTicket = null
        updateJob?.cancel()
        updateJob = null
        if (active) _state.update { it.copy(updates = ProjectUpdateState.Cancelled) }
    }

    @MainThread
    fun requestExport() {
        if (!visible || _state.value.page != HelpPage.DIAGNOSTICS || !_state.value.canExport ||
            pickerToken != null || launchedPickerToken != null) return
        val token = UUID.randomUUID().toString()
        pickerToken = token
        pickerLaunched = false
        saved[KEY_PICKER] = token
        saved[KEY_PICKER_LAUNCHED] = false
        _state.update { it.copy(pickerRequest = token, waitingForPicker = true, feedback = null) }
    }

    @MainThread
    fun pickerLaunched(token: String): Boolean {
        if (!visible || pickerToken != token || pickerLaunched || _state.value.page != HelpPage.DIAGNOSTICS) return false
        pickerLaunched = true
        launchedPickerToken = token
        launchedPickerExpired = false
        saved[KEY_PICKER_LAUNCHED] = true
        saved[KEY_LAUNCH_TOKEN] = token
        saved[KEY_LAUNCH_EXPIRED] = false
        _state.update { it.copy(pickerRequest = null) }
        return true
    }

    @MainThread
    fun pickerUnavailable(token: String) {
        if (launchedPickerToken != token) return
        releaseLaunchSlot()
        retirePicker()
        _state.update { it.copy(feedback = HelpFeedback.NO_FOLDER_PICKER) }
    }

    @MainThread
    fun destinationReturned(token: String?, uri: Uri?, flags: Int) {
        if (token == null) {
            // 未能恢复对应launch绑定的系统结果只退役实际callback槽，不采用其中的URI。
            releaseLaunchSlot()
            retirePicker()
            return
        }
        if (launchedPickerToken != token) return
        val accepted = pickerToken == token && !launchedPickerExpired && _state.value.page == HelpPage.DIAGNOSTICS
        releaseLaunchSlot()
        retirePicker()
        if (!accepted) return
        if (uri == null) {
            _state.update { it.copy(feedback = HelpFeedback.CANCELLED) }
        } else {
            val destination = LocalArtifactDocumentDestination(uri, flags)
            if (visible) exportTo(destination) else returnedDestination = destination
        }
    }

    @MainThread
    fun clearDiagnostics() = work {
        app.diagnostics.clear()
        if (currentWorkUi() && _state.value.page == HelpPage.DIAGNOSTICS) {
            _state.update { it.copy(diagnostics = emptyList(), feedback = HelpFeedback.SAVED) }
        }
    }

    @MainThread
    fun recoverExport() = work {
        app.localArtifactDocuments.recoverPendingOutput()
        app.diagnostics.recoverRetiredExport()
        if (currentWorkUi()) _state.update { it.copy(feedback = HelpFeedback.SAVED) }
    }

    @MainThread
    fun externalUnavailable() { _state.update { it.copy(feedback = HelpFeedback.EXTERNAL_UNAVAILABLE) } }

    @MainThread
    fun retainUnboundLaunchSlot(token: String?) {
        if (token == null || !uuid(token) || launchedPickerToken != null) return
        launchedPickerToken = token
        pickerLaunched = true
        launchedPickerExpired = true
        saved[KEY_LAUNCH_TOKEN] = token
        saved[KEY_LAUNCH_EXPIRED] = true
        _state.update { it.copy(waitingForPicker = true) }
    }

    @MainThread
    fun retryPage() { if (visible && !_state.value.reading) loadPage() }

    private fun writeHelp(transform: (HelpPreferences) -> HelpPreferences, after: () -> Unit = {}) {
        val ready = _state.value.storage as? PreferencesState.Ready ?: return
        if (!visible || !ready.canWrite || ready.recovery != null || workTicket != null) return
        val page = _state.value.page
        val draft = ready.preferences.asDraft().copy(help = transform(ready.preferences.help))
        work {
            val result = preferences.save(ready.preferences.revision, draft)
            if (!currentWorkUi() || _state.value.page != page) return@work
            when (result) {
                is PreferencesWriteResult.Saved, is PreferencesWriteResult.Unchanged -> {
                    _state.update { it.copy(feedback = HelpFeedback.SAVED) }
                    after()
                }
                is PreferencesWriteResult.Conflict -> _state.update { it.copy(feedback = HelpFeedback.CONFLICT) }
                is PreferencesWriteResult.Failed -> _state.update { it.copy(feedback = HelpFeedback.SETTINGS_UNAVAILABLE) }
                is PreferencesWriteResult.Cancelled -> _state.update { it.copy(feedback = HelpFeedback.CANCELLED) }
            }
        }
    }

    private fun exportTo(destination: LocalArtifactDocumentDestination) = work {
        app.localArtifactDocuments.recoverPendingOutput()
        val export = app.diagnostics.prepareExport(_state.value.version)
        var operationFailure: Throwable? = null
        try {
            val source = LocalArtifactSource.diagnostic(export.file, export.artifactId, export.bytes, export.sha256)
            val receipt = app.localArtifactDocuments.writeArtifact(destination, source)
            check(receipt.artifactId == export.artifactId && receipt.sha256 == export.sha256 && receipt.bytes == export.bytes)
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            // writeArtifact只在同一真实I/O退出后完成；不能在provider仍读文件时清掉源。
            try { withContext(NonCancellable) { app.diagnostics.discard(export) } }
            catch (cleanup: Exception) {
                val failure = operationFailure
                if (failure is CancellationException) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                } else {
                    if (failure != null && failure !== cleanup) cleanup.addSuppressed(failure)
                    throw cleanup
                }
            }
        }
        if (currentWorkUi() && _state.value.page == HelpPage.DIAGNOSTICS) {
            _state.update { it.copy(feedback = HelpFeedback.EXPORTED) }
        }
    }

    private fun work(action: suspend () -> Unit) {
        if (!visible || workTicket != null) return
        val ticket = Any()
        workTicket = ticket
        workPresentation = presentation
        _state.update { it.copy(working = true, feedback = null) }
        workJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
            } catch (failure: CancellationException) {
                if (currentWorkUi()) _state.update { it.copy(feedback = HelpFeedback.CANCELLED) }
                throw failure
            } catch (failure: LocalArtifactException) {
                if (currentWorkUi()) _state.update { it.copy(feedback = when (failure.issue) {
                    LocalArtifactIssue.LOCAL_PROVIDER_REQUIRED -> HelpFeedback.LOCAL_FOLDER_REQUIRED
                    LocalArtifactIssue.OUTPUT_CLEANUP_REQUIRED -> HelpFeedback.CLEANUP_REQUIRED
                    else -> HelpFeedback.IO
                }) }
            } catch (_: DiagnosticSourceRetirementException) {
                if (currentWorkUi()) _state.update { it.copy(feedback = HelpFeedback.CLEANUP_REQUIRED) }
            } catch (_: Exception) {
                if (currentWorkUi()) _state.update { it.copy(feedback = HelpFeedback.IO) }
            } finally {
                if (workTicket === ticket) {
                    workTicket = null
                    workPresentation = null
                    workJob = null
                    _state.update { it.copy(working = false) }
                }
            }
        }
    }

    private fun loadPage() {
        cancelReads()
        val page = _state.value.page
        if (page !in setOf(HelpPage.LICENSES, HelpPage.LICENSE_TEXT, HelpPage.DIAGNOSTICS)) return
        val ticket = Any()
        readTicket = ticket
        _state.update { it.copy(reading = true) }
        readJob = viewModelScope.launch {
            try {
                when (page) {
                    HelpPage.LICENSES -> {
                        val entries = app.appLicenses.entries()
                        if (readTicket === ticket && visible) _state.update { it.copy(licenses = entries) }
                    }
                    HelpPage.LICENSE_TEXT -> {
                        val entries = _state.value.licenses.ifEmpty { app.appLicenses.entries() }
                        val entry = entries.firstOrNull { it.id == _state.value.licenseId } ?: error("Unknown packaged license")
                        val text = app.appLicenses.notice(entry)
                        val lines = withContext(Dispatchers.Default) {
                            val context = currentCoroutineContext()
                            text.lines().flatMap { line ->
                                context.ensureActive()
                                val pieces = ArrayList<String>()
                                var start = 0
                                var remaining = line.codePointCount(0, line.length)
                                while (remaining > 0) {
                                    context.ensureActive()
                                    val count = minOf(80, remaining)
                                    val end = line.offsetByCodePoints(start, count)
                                    pieces.add(line.substring(start, end))
                                    start = end
                                    remaining -= count
                                }
                                pieces.ifEmpty { listOf("") }
                            }
                        }
                        if (readTicket === ticket && visible) _state.update { it.copy(licenses = entries, licenseLines = lines) }
                    }
                    HelpPage.DIAGNOSTICS -> {
                        val events = app.diagnostics.events()
                        if (readTicket === ticket && visible) _state.update { it.copy(diagnostics = events) }
                    }
                    else -> Unit
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (readTicket === ticket && visible) _state.update { it.copy(feedback =
                    if (page == HelpPage.DIAGNOSTICS) HelpFeedback.IO else HelpFeedback.LICENSE_UNAVAILABLE) }
            } finally {
                if (readTicket === ticket) {
                    readTicket = null
                    readJob = null
                    _state.update { it.copy(reading = false) }
                }
            }
        }
    }

    private fun cancelReads() {
        readTicket = null
        readJob?.cancel()
        readJob = null
        _state.update { it.copy(reading = false) }
    }

    private fun retirePicker() {
        pickerToken = null
        returnedDestination = null
        saved.remove<String>(KEY_PICKER)
        if (launchedPickerToken != null) {
            launchedPickerExpired = true
            saved[KEY_LAUNCH_EXPIRED] = true
        } else {
            pickerLaunched = false
            saved.remove<Boolean>(KEY_PICKER_LAUNCHED)
        }
        _state.update { it.copy(pickerRequest = null, waitingForPicker = launchedPickerToken != null) }
    }

    private fun releaseLaunchSlot() {
        launchedPickerToken = null
        launchedPickerExpired = false
        pickerLaunched = false
        saved.remove<String>(KEY_LAUNCH_TOKEN)
        saved.remove<Boolean>(KEY_LAUNCH_EXPIRED)
        saved.remove<Boolean>(KEY_PICKER_LAUNCHED)
    }

    private fun currentWorkUi(): Boolean = visible && workTicket != null && workPresentation === presentation

    private fun uuid(value: String): Boolean = value.length == 36 &&
        try { UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }

    private companion object {
        const val KEY_PAGE = "help.page"
        const val KEY_GUIDE = "help.guide.step"
        const val KEY_LICENSE = "help.license.id"
        const val KEY_PICKER = "help.export.picker"
        const val KEY_PICKER_LAUNCHED = "help.export.picker.launched"
        const val KEY_LAUNCH_TOKEN = "help.export.system.launch"
        const val KEY_LAUNCH_EXPIRED = "help.export.system.expired"
    }
}
