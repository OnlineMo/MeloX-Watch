package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.auth.VerifiedSession
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.OnlinePlaybackResolver
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResourceStatus
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import io.github.onlinemo.meloxwatch.playback.resources.ResourceMatch
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResource
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The account token remains inside this adapter; the optional service never receives a NetEase credential. */
internal class NeteasePlaybackResolver(
    private val sessions: SessionRepository,
    private val login: NeteaseAuthApi,
    private val api: NeteasePlaybackApi,
    private val resources: RuntimePlaybackResources,
    private val supplemental: suspend () -> OnlinePlaybackResolver? = { null },
) : OnlinePlaybackResolver {
    override suspend fun resolve(entry: QueueEntry, budget: PlaybackResolutionBudget, quality: StreamingQuality): RuntimePlaybackResource {
        var pending: RuntimePlaybackResource? = null
        try {
            return budget.within(onAbandoned = RuntimePlaybackResource::close) {
                val restored = sessions.restore()
                // An old account's durable queue is a placeholder, not permission to expire the current account.
                if (restored.account != null && (restored.account.providerId != entry.trackKey.providerId ||
                    restored.account.accountId != entry.accountId)) throw playbackFailure(QueuePlaybackIssue.LOGIN_REQUIRED, entry)
                if (restored.validity == SessionValidity.RESTORED_UNVERIFIED) {
                    val verifier = object : NeteaseLoginApi {
                        override suspend fun createKey(): LoginQrKey = throw AuthException(AuthIssue.INVALID_RESPONSE)
                        override suspend fun checkKey(key: LoginQrKey): QrCheckResult = throw AuthException(AuthIssue.INVALID_RESPONSE)
                        override suspend fun verify(credential: SessionCredential): VerifiedSession = login.verify(credential, budget.metadataCall())
                    }
                    if (!sessions.revalidate(verifier)) throw AuthException(AuthIssue.LOGIN_REQUIRED)
                } else if (restored.validity == SessionValidity.UNAVAILABLE) {
                    throw AuthException(restored.issue ?: AuthIssue.KEY_UNAVAILABLE)
                }
                sessions.authenticated { credential, token ->
                    if (token.account.providerId != entry.trackKey.providerId || token.account.accountId != entry.accountId) {
                        throw playbackFailure(QueuePlaybackIssue.LOGIN_REQUIRED, entry)
                    }
                    var selected: NeteaseResolvedSource? = null
                    var nativeFailure: QueuePlaybackException? = null
                    try { selected = api.resolve(entry.trackKey.trackId, quality, credential, budget) }
                    catch (error: QueuePlaybackException) {
                        if (error.failure.issue != QueuePlaybackIssue.RESOURCE_UNAVAILABLE) throw error
                        nativeFailure = error
                    }
                    if (selected == null && quality == StreamingQuality.HIGH) {
                        try { selected = api.resolve(entry.trackKey.trackId, StreamingQuality.STANDARD, credential, budget) }
                        catch (error: QueuePlaybackException) {
                            if (error.failure.issue != QueuePlaybackIssue.RESOURCE_UNAVAILABLE) throw error
                            nativeFailure = error
                        }
                    }
                    val isPreview = selected?.completeness == ResourceCompleteness.PREVIEW_REPORTED
                    val supplement = if (selected == null || isPreview) supplemental() else null
                    if (supplement != null) {
                        budget.claimSupplementalRequest()
                        // Cancellation/authentication/429/certificate failures never arrive in this fallback branch.
                        return@authenticated budget.within(onAbandoned = RuntimePlaybackResource::close) {
                            supplement.resolve(entry, budget, quality).also { pending = it }
                        }.also {
                            if (it.entry.trackKey != entry.trackKey || it.entry.accountId != entry.accountId ||
                                it.entry.queueEntryId != entry.queueEntryId) {
                                it.close()
                                throw playbackFailure(QueuePlaybackIssue.SOURCE_CHANGED, entry)
                            }
                        }
                    }
                    val source = selected ?: throw (nativeFailure ?: playbackFailure(QueuePlaybackIssue.RESOURCE_UNAVAILABLE, entry))
                    currentCoroutineContext().ensureActive()
                    budget.remainingMs()
                    if (!sessions.isCurrent(token)) throw playbackFailure(QueuePlaybackIssue.LOGIN_REQUIRED, entry)
                    resources.register(entry, source.target, budget, source.expiresElapsedMs,
                        PlaybackResourceStatus("netease-native", ResourceMatch.PROVIDER_REPORTED, source.completeness,
                            quality, source.reportedQuality, source.reportedBitrate),
                        accountCurrent = { sessions.isCurrent(token) }).also { pending = it }
                }
            }.also { pending = null }
        } catch (error: AuthException) { throw playbackFailure(mapAuthIssue(error.issue), entry) }
        catch (error: QueuePlaybackException) {
            throw QueuePlaybackException(error.failure.copy(entryId = entry.queueEntryId))
        }
        finally { pending?.close() }
    }

    private fun playbackFailure(issue: QueuePlaybackIssue, entry: QueueEntry) = QueuePlaybackException(QueuePlaybackFailure(issue, entry.queueEntryId))
    private fun mapAuthIssue(issue: AuthIssue): QueuePlaybackIssue = when (issue) {
        AuthIssue.NETWORK_UNAVAILABLE -> QueuePlaybackIssue.NETWORK_UNAVAILABLE
        AuthIssue.TIMEOUT -> QueuePlaybackIssue.NETWORK_TIMEOUT
        AuthIssue.RATE_LIMITED -> QueuePlaybackIssue.NETWORK_RATE_LIMITED
        AuthIssue.CERTIFICATE -> QueuePlaybackIssue.NETWORK_CERTIFICATE
        AuthIssue.LOGIN_REQUIRED -> QueuePlaybackIssue.LOGIN_REQUIRED
        AuthIssue.INVALID_RESPONSE -> QueuePlaybackIssue.RESOURCE_INVALID
        AuthIssue.BUSY -> QueuePlaybackIssue.BUSY
        AuthIssue.CLOSED -> QueuePlaybackIssue.CLOSED
        AuthIssue.KEY_UNAVAILABLE, AuthIssue.STORAGE_UNAVAILABLE, AuthIssue.SESSION_CORRUPT -> QueuePlaybackIssue.SESSION_UNAVAILABLE
    }
}
