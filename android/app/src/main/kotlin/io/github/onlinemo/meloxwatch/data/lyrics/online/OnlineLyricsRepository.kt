package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslation
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslationMatcher
import io.github.onlinemo.meloxwatch.data.meting.MetingException
import io.github.onlinemo.meloxwatch.data.meting.MetingIssue
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.meting.MetingConfigurationUse
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsException
import io.github.onlinemo.meloxwatch.data.netease.NeteaseLyricsIssue
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.OpenedPlaybackResourceMetadata
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackAssociatedKind
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One visible current intent reads an account-scoped cache or its actual source. Native lyrics
 * never invoke Meting; a service lyric target is reachable only through an actually opened
 * Meting resource. This owner has no media clock, player, automatic retry or preload task.
 */
internal class OnlineLyricsRepository(
    private val sessions: SessionRepository,
    private val native: NeteaseLyricsApi,
    private val resources: RuntimePlaybackResources,
    private val meting: MetingRuntimeOwner,
    private val cache: DiskOnlineLyricsCache,
    private val networkAvailable: suspend () -> Boolean,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : OnlineLyricsPort, AutoCloseable {
    private val operation = Mutex()
    private val memory = Any()
    private var closed = false
    private var requestJob: Job? = null

    override val changes: Flow<Unit> = combine(sessions.snapshot, resources.openedResource, meting.configuration.snapshot) {
        account, opened, configured ->
        SourceEpoch(account.generation, account.validity, account.account?.let { ownerHash(it.providerId, it.accountId) },
            opened, if (opened?.resolverId == METING_RESOLVER)
                ConfigurationEpoch(configured.generation, configured.loaded, configured.enabled, configured.changing, configured.issue) else null)
    }.distinctUntilChanged().map { Unit }

    override suspend fun load(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult {
        if (!operation.tryLock()) return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.BUSY)
        try {
            val job = currentCoroutineContext()[Job]
            synchronized(memory) {
                if (closed) return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.CLOSED)
                requestJob = job
            }
            return withTimeoutOrNull(REQUEST_MS) { loadCurrent(request, isCurrent) }
                ?: OnlineLyricsResult.Unavailable(OnlineLyricsIssue.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: LyricsFetchException) { return OnlineLyricsResult.Unavailable(error.issue) }
        catch (error: NeteaseLyricsException) { return OnlineLyricsResult.Unavailable(nativeIssue(error.issue)) }
        catch (error: LyricsParseException) { return OnlineLyricsResult.Unavailable(parseIssue(error)) }
        catch (error: AuthException) { return OnlineLyricsResult.Unavailable(authIssue(error.issue)) }
        catch (error: MetingException) { return OnlineLyricsResult.Unavailable(metingIssue(error.issue)) }
        catch (error: QueuePlaybackException) { return OnlineLyricsResult.Unavailable(when (error.failure.issue) {
            QueuePlaybackIssue.LOGIN_REQUIRED -> OnlineLyricsIssue.LOGIN_REQUIRED
            QueuePlaybackIssue.RESOURCE_EXPIRED, QueuePlaybackIssue.SOURCE_CHANGED -> OnlineLyricsIssue.SOURCE_CHANGED
            else -> OnlineLyricsIssue.READ_FAILED
        }) }
        catch (_: IOException) { return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.READ_FAILED) }
        catch (_: OutOfMemoryError) { return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.MEMORY_LIMIT) }
        catch (_: Exception) { return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.READ_FAILED) }
        finally {
            synchronized(memory) { requestJob = null }
            operation.unlock()
        }
    }

    private suspend fun loadCurrent(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult {
        checkCurrent(isCurrent)
        val restored = sessions.restore()
        checkCurrent(isCurrent)
        val account = restored.account ?: throw LyricsFetchException(if (restored.validity == SessionValidity.UNAVAILABLE)
            OnlineLyricsIssue.SESSION_UNAVAILABLE else OnlineLyricsIssue.LOGIN_REQUIRED)
        if (account.providerId != request.trackKey.providerId || account.accountId != request.accountId ||
            restored.validity !in READABLE_ACCOUNT) throw LyricsFetchException(OnlineLyricsIssue.LOGIN_REQUIRED)
        val owner = ownerHash(account.providerId, account.accountId)
        val opened = resources.openedResource.value
        if (opened != null && (opened.queueEntryId != request.queueEntryId || opened.providerId != request.trackKey.providerId ||
                opened.trackId != request.trackKey.trackId || opened.accountId != request.accountId)) {
            throw LyricsFetchException(OnlineLyricsIssue.SOURCE_CHANGED)
        }
        val origin = when (opened?.resolverId) {
            null, NATIVE_RESOLVER -> OnlineLyricsOrigin.NETEASE_LRC
            METING_RESOLVER -> OnlineLyricsOrigin.OPENED_METING_LRC
            else -> throw LyricsFetchException(OnlineLyricsIssue.UNSUPPORTED)
        }
        val service = if (origin == OnlineLyricsOrigin.OPENED_METING_LRC) {
            val configured = meting.configuration.snapshot.value
            if (!configured.loaded || !configured.enabled || configured.changing || configured.issue != null ||
                configured.generation != opened?.configurationGeneration) throw LyricsFetchException(OnlineLyricsIssue.SOURCE_CHANGED)
            val use = meting.configuration.capture(requireEnabled = true)
                ?: throw LyricsFetchException(OnlineLyricsIssue.SOURCE_CHANGED)
            checkCurrent(isCurrent)
            if (use.generation != configured.generation) throw LyricsFetchException(OnlineLyricsIssue.SOURCE_CHANGED)
            use.checkCurrent()
            use
        } else null
        val configuration = service?.generation
        val fence = OnlineLyricsFence(request.trackKey, request.queueGenerationId, request.queueRevision, request.queueEntryId,
            request.sourceGeneration, owner, restored.generation, origin, configuration, opened?.operationId)
        val key = OnlineLyricsCacheKey.of(owner, request.trackKey.trackId, origin, configuration, service?.let(::serviceFingerprint))
        val textAllowance = request.maxAccountedBytes - PARSE_ENVELOPE_BYTES
        if (textAllowance <= 0L) throw LyricsFetchException(OnlineLyricsIssue.MEMORY_LIMIT)
        var invalidCache = false
        val retained = try { cache.read(key, textAllowance) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { invalidCache = true; null }
        ensureSource(fence, opened, isCurrent, requireVerified = false)
        if (retained != null) return parse(request, fence, retained, fromCache = true, isCurrent = {
            sourceCurrent(fence, opened, requireVerified = false) && isCurrent()
        })
        if (!request.allowNetwork) return OnlineLyricsResult.Unavailable(
            if (invalidCache) OnlineLyricsIssue.READ_FAILED else OnlineLyricsIssue.NO_LYRICS)
        if (restored.validity != SessionValidity.VERIFIED) throw LyricsFetchException(OnlineLyricsIssue.LOGIN_REQUIRED)
        if (!networkAvailable()) throw LyricsFetchException(OnlineLyricsIssue.OFFLINE)
        ensureSource(fence, opened, isCurrent, requireVerified = true)
        // No speculative native lyric call while the active audio resolver is still unknown.
        if (opened == null) throw LyricsFetchException(OnlineLyricsIssue.SOURCE_CHANGED)
        val text: OnlineLyricsText = when (origin) {
            OnlineLyricsOrigin.NETEASE_LRC -> sessions.authenticated { credential, token ->
                if (token.generation != fence.accountGeneration || ownerHash(token.account.providerId, token.account.accountId) != fence.ownerHash) {
                    throw CancellationException("Online lyrics account changed")
                }
                val result = try { native.fetch(request.trackKey.trackId, credential, request.maxAccountedBytes) }
                catch (error: AuthException) {
                    // An auxiliary lyric denial is not authoritative evidence that healthy audio's account expired.
                    throw LyricsFetchException(authIssue(error.issue))
                }
                ensureSource(fence, opened, isCurrent, requireVerified = true)
                if (!sessions.isCurrent(token)) throw CancellationException("Online lyrics account changed")
                val primary = result.primary ?: return@authenticated null
                OnlineLyricsText(primary, result.translation, result.translationIssue?.let(::nativeIssue))
            }
            OnlineLyricsOrigin.OPENED_METING_LRC -> {
                val allowed = ((request.maxAccountedBytes - PARSE_ENVELOPE_BYTES) / 6L)
                    .coerceAtMost(LyricsLimits.INPUT_BYTES.toLong())
                if (allowed <= 0L) throw LyricsFetchException(OnlineLyricsIssue.MEMORY_LIMIT)
                meting.readOpenedAssociated(request.queueEntryId, PlaybackAssociatedKind.LYRICS, allowed.toInt())
                    ?.let { OnlineLyricsText(it, null) }
            }
        } ?: return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.NO_LYRICS)
        ensureSource(fence, opened, isCurrent, requireVerified = true)
        val result = parse(request, fence, text, fromCache = false) {
            sourceCurrent(fence, opened, requireVerified = true) && isCurrent()
        }
        ensureSource(fence, opened, isCurrent, requireVerified = true)
        // A failed auxiliary translation keeps the primary visible but cannot replace a good cache;
        // the explicit Retry can obtain a corrected response instead of being pinned to the failure.
        try { if (result.translationIssue == null) cache.write(key, text) { sourceCurrent(fence, opened, requireVerified = true) && isCurrent() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { /* The current parsed display remains usable when its owned cache cannot be written. */ }
        ensureSource(fence, opened, isCurrent, requireVerified = true)
        return result
    }

    private suspend fun parse(request: OnlineLyricsRequest, fence: OnlineLyricsFence, text: OnlineLyricsText,
        fromCache: Boolean, isCurrent: () -> Boolean): OnlineLyricsResult.Loaded {
        checkCurrent(isCurrent)
        val maximum = request.maxAccountedBytes
        val primaryBytes = text.primary.size.toLong()
        val translationBytes = text.translation?.size?.toLong() ?: 0L
        val primaryAllowance = maximum - PARSE_ENVELOPE_BYTES - translationBytes
        if (primaryAllowance <= 0L) throw LyricsFetchException(OnlineLyricsIssue.MEMORY_LIMIT)
        val primary = LocalLyricsParser(LyricsLimits(maxAccountedBytes = primaryAllowance), workDispatcher).parse(text.primary)
        checkCurrent(isCurrent)
        var peak = PARSE_ENVELOPE_BYTES + translationBytes + primaryBytes * 6L + primary.accountedResidentBytes
        if (peak > maximum) throw LyricsFetchException(OnlineLyricsIssue.MEMORY_LIMIT)
        var translated: LyricsTranslation? = null
        var translationIssue: OnlineLyricsIssue? = text.translationIssue
        val translation = text.translation
        if (translation != null && translation.isNotEmpty()) {
            try {
                val allowance = maximum - PARSE_ENVELOPE_BYTES - primaryBytes - primary.accountedResidentBytes
                if (allowance <= 0L) throw LyricsFetchException(OnlineLyricsIssue.MEMORY_LIMIT)
                val document = LocalLyricsParser(LyricsLimits(maxAccountedBytes = allowance), workDispatcher).parse(translation)
                checkCurrent(isCurrent)
                val candidatePeak = PARSE_ENVELOPE_BYTES + primaryBytes + primary.accountedResidentBytes +
                    translationBytes * 6L + document.accountedResidentBytes
                val pairingAllowance = maximum - PARSE_ENVELOPE_BYTES - primaryBytes - translationBytes
                if (candidatePeak > maximum || pairingAllowance <= 0L) throw LyricsFetchException(OnlineLyricsIssue.MEMORY_LIMIT)
                val matched: LyricsTranslation = LyricsTranslationMatcher(LyricsLimits(maxAccountedBytes = pairingAllowance), workDispatcher)
                    .match(primary, document)
                translated = matched
                peak = maxOf(peak, candidatePeak, PARSE_ENVELOPE_BYTES + primaryBytes + translationBytes + matched.accountedResidentBytes)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LyricsParseException) { translationIssue = parseIssue(error) }
            catch (error: LyricsFetchException) { translationIssue = error.issue }
        }
        checkCurrent(isCurrent)
        if (peak > maximum) throw LyricsFetchException(OnlineLyricsIssue.MEMORY_LIMIT)
        return OnlineLyricsResult.Loaded(fence, primary, translated, peak, fromCache, translationIssue)
    }

    override suspend fun validate(loaded: OnlineLyricsResult.Loaded): Boolean {
        currentCoroutineContext().ensureActive()
        val fence = loaded.fence
        val opened = resources.openedResource.value
        if (opened?.operationId != fence.openedOperationId) return false
        return sourceCurrent(fence, opened, requireVerified = true)
    }

    private fun sourceCurrent(fence: OnlineLyricsFence, opened: OpenedPlaybackResourceMetadata?, requireVerified: Boolean): Boolean {
        if (synchronized(memory) { closed }) return false
        val account = sessions.snapshot.value
        val identity = account.account ?: return false
        if (identity.providerId != fence.trackKey.providerId || ownerHash(identity.providerId, identity.accountId) != fence.ownerHash ||
            account.generation != fence.accountGeneration || account.validity !in READABLE_ACCOUNT ||
            requireVerified && account.validity != SessionValidity.VERIFIED || resources.openedResource.value !== opened) return false
        if (opened != null && (opened.operationId != fence.openedOperationId || opened.queueEntryId != fence.queueEntryId ||
                opened.providerId != fence.trackKey.providerId || opened.trackId != fence.trackKey.trackId ||
                requireVerified && !opened.isCurrentAccount())) return false
        if (fence.origin == OnlineLyricsOrigin.OPENED_METING_LRC) {
            val configured = meting.configuration.snapshot.value
            if (opened?.resolverId != METING_RESOLVER || configured.generation != fence.configurationGeneration ||
                !configured.loaded || !configured.enabled || configured.changing || configured.issue != null) return false
        } else if (opened != null && opened.resolverId != NATIVE_RESOLVER) return false
        return true
    }

    private suspend fun ensureSource(fence: OnlineLyricsFence, opened: OpenedPlaybackResourceMetadata?,
        isCurrent: () -> Boolean, requireVerified: Boolean) {
        checkCurrent(isCurrent)
        if (!sourceCurrent(fence, opened, requireVerified)) throw CancellationException("Online lyrics source changed")
    }

    private suspend fun checkCurrent(isCurrent: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent() || synchronized(memory) { closed }) throw CancellationException("Online lyrics intent retired")
    }

    override fun close() {
        val pending = synchronized(memory) { closed = true; requestJob }
        pending?.cancel(CancellationException("Online lyrics owner closed"))
    }

    private data class SourceEpoch(val generation: Long, val validity: SessionValidity, val owner: String?,
        val opened: OpenedPlaybackResourceMetadata?, val configuration: ConfigurationEpoch?)
    private data class ConfigurationEpoch(val generation: Long, val loaded: Boolean, val enabled: Boolean,
        val changing: Boolean, val issue: MetingIssue?)
    private class LyricsFetchException(val issue: OnlineLyricsIssue) : Exception(issue.name)

    /** A deleted service can reuse a generation after restart. Only its bounded private hash is retained. */
    private fun serviceFingerprint(use: MetingConfigurationUse): String {
        val configuration = use.configuration
        val parts = listOf(configuration.endpoint.storedAddress, configuration.endpoint.mode.name,
            configuration.allowCleartext.toString(), configuration.credential?.name.orEmpty(),
            configuration.credential?.storedValue().orEmpty())
        return onlineOpaqueHash(parts.joinToString("") { "${it.length}:$it" })
    }
    private companion object {
        const val REQUEST_MS = 8_000L
        const val PARSE_ENVELOPE_BYTES = 65_536L
        const val NATIVE_RESOLVER = "netease-native"
        const val METING_RESOLVER = "meting-custom"
        val READABLE_ACCOUNT = setOf(SessionValidity.VERIFIED, SessionValidity.RESTORED_UNVERIFIED)
        fun ownerHash(provider: String, account: String): String = onlineOpaqueHash("$provider:$account")
        fun nativeIssue(issue: NeteaseLyricsIssue): OnlineLyricsIssue = when (issue) {
            NeteaseLyricsIssue.INPUT_TOO_LARGE -> OnlineLyricsIssue.INPUT_LIMIT
            NeteaseLyricsIssue.MEMORY_LIMIT -> OnlineLyricsIssue.MEMORY_LIMIT
            NeteaseLyricsIssue.INVALID_RESPONSE -> OnlineLyricsIssue.READ_FAILED
        }
        fun parseIssue(error: LyricsParseException): OnlineLyricsIssue = when (error.issue) {
            LyricsParseIssue.INPUT_TOO_LARGE, LyricsParseIssue.TOO_MANY_LINES, LyricsParseIssue.TOO_MANY_TIMED_LINES,
            LyricsParseIssue.TOO_MANY_WORD_EVENTS, LyricsParseIssue.LINE_TOO_LONG -> OnlineLyricsIssue.INPUT_LIMIT
            LyricsParseIssue.MEMORY_LIMIT -> OnlineLyricsIssue.MEMORY_LIMIT
            LyricsParseIssue.INVALID_ENCODING, LyricsParseIssue.ENCODING_MISMATCH,
            LyricsParseIssue.UNSUPPORTED_ENCODING -> OnlineLyricsIssue.INVALID_ENCODING
            else -> OnlineLyricsIssue.READ_FAILED
        }
        fun authIssue(issue: AuthIssue): OnlineLyricsIssue = when (issue) {
            AuthIssue.NETWORK_UNAVAILABLE -> OnlineLyricsIssue.OFFLINE
            AuthIssue.TIMEOUT -> OnlineLyricsIssue.TIMEOUT
            AuthIssue.CERTIFICATE -> OnlineLyricsIssue.CERTIFICATE
            AuthIssue.RATE_LIMITED -> OnlineLyricsIssue.RATE_LIMITED
            AuthIssue.LOGIN_REQUIRED -> OnlineLyricsIssue.LOGIN_REQUIRED
            AuthIssue.BUSY -> OnlineLyricsIssue.BUSY
            AuthIssue.CLOSED -> OnlineLyricsIssue.CLOSED
            else -> OnlineLyricsIssue.SESSION_UNAVAILABLE
        }
        fun metingIssue(issue: MetingIssue): OnlineLyricsIssue = when (issue) {
            MetingIssue.NETWORK_UNAVAILABLE -> OnlineLyricsIssue.OFFLINE
            MetingIssue.NETWORK_TIMEOUT -> OnlineLyricsIssue.TIMEOUT
            MetingIssue.CERTIFICATE -> OnlineLyricsIssue.CERTIFICATE
            MetingIssue.RATE_LIMITED -> OnlineLyricsIssue.RATE_LIMITED
            MetingIssue.RESPONSE_TOO_LARGE -> OnlineLyricsIssue.INPUT_LIMIT
            MetingIssue.CONFIGURATION_CHANGED, MetingIssue.CREDENTIAL_ORIGIN_CHANGED -> OnlineLyricsIssue.SOURCE_CHANGED
            MetingIssue.AUTH_REQUIRED -> OnlineLyricsIssue.LOGIN_REQUIRED
            MetingIssue.EMPTY_RESOURCE -> OnlineLyricsIssue.NO_LYRICS
            MetingIssue.BUSY -> OnlineLyricsIssue.BUSY
            MetingIssue.CLOSED -> OnlineLyricsIssue.CLOSED
            else -> OnlineLyricsIssue.READ_FAILED
        }
    }
}
