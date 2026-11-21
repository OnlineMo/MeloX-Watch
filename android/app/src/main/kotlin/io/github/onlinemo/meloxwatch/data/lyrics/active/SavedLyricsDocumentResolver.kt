package io.github.onlinemo.meloxwatch.data.lyrics.active

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsDocumentInput
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackLimits
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 预览只观察一次授权和原文本，不持久化权限。确认后才创建私有副本并交出提交 pin；
 * 调用者在真实数据库提交结束（含取消清理）后释放，不能由页面隐藏提前回收。
 */
interface LyricsDocuments {
    suspend fun preview(uri: String, returnedFlags: Int, generation: Long, encoding: LyricsEncodingChoice): LyricsDocumentInput
    suspend fun resolve(association: LyricsAssociation, generation: Long): LyricsDocumentInput
    /** 仅供明确确认调用；副本在数据库引用确认前保持 pin，release 不删已提交数据。 */
    suspend fun prepareAssociation(input: LyricsDocumentInput, confirmedSha256: String): PreparedLyricsAssociation
}

class PreparedLyricsAssociation(
    val association: LyricsAssociation,
    val input: LyricsDocumentInput,
    private val releasePin: () -> Unit,
) {
    private val released = AtomicBoolean(false)
    fun release() { if (released.compareAndSet(false, true)) releasePin() }
}

class LyricsDocumentResolutionException(val issue: LyricsLoadIssue) : Exception(issue.name)

/** 保存的 URI 与权限标记只用于重新观察；授权、对象和确认字节每次都独立复核。 */
class SavedLyricsDocumentResolver(
    context: Context,
    private val sources: LocalSourceAccess,
    private val copies: LyricsDocumentCopies,
    private val verifyCandidate: suspend (LocalSourceSpec, io.github.onlinemo.meloxwatch.data.local.ObservedCandidate) -> Unit,
) : LyricsDocuments {
    private val context = context.applicationContext

    override suspend fun preview(
        uri: String,
        returnedFlags: Int,
        generation: Long,
        encoding: LyricsEncodingChoice,
    ): LyricsDocumentInput {
        validateUri(uri)
        require(generation >= 0L)
        if (returnedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) fail(LyricsLoadIssue.PERMISSION_DENIED)
        val source = LocalSourceSpec("lyrics:${UUID.randomUUID()}", LocalSourceKind.DOCUMENT, uri, returnedFlags)
        val observed = sources.observe(source)
        validateObservation(source, observed.source, observed.root.directory, observed.root.sizeBytes)
        return LyricsDocumentInput(observed.root.objectId, generation, observed.source, observed.root, encoding)
    }

    override suspend fun resolve(association: LyricsAssociation, generation: Long): LyricsDocumentInput {
        val uri = validateUri(association.uri)
        val owned = OwnedLyricsProvider.token(context, uri) != null
        if (association.readFlags != LyricsTrackLimits.READ_PERMISSION_FLAG && !owned) {
            fail(LyricsLoadIssue.PERMISSION_REVOKED)
        }
        val source = LocalSourceSpec(association.sourceId, LocalSourceKind.DOCUMENT, association.uri, association.readFlags)
        val observed = sources.observe(source)
        validateObservation(source, observed.source, observed.root.directory, observed.root.sizeBytes)
        if (observed.root.objectId != association.documentId || observed.root.uri != association.uri) {
            fail(LyricsLoadIssue.SOURCE_CHANGED)
        }
        if (!owned && observed.access.persistedRead != ObservedCapability.AVAILABLE) {
            fail(LyricsLoadIssue.PERMISSION_REVOKED)
        }
        return LyricsDocumentInput(association.documentId, generation, observed.source, observed.root,
            association.encoding, association.confirmedContentSha256, requirePersistedRead = !owned)
    }

    override suspend fun prepareAssociation(input: LyricsDocumentInput, confirmedSha256: String): PreparedLyricsAssociation {
        require(confirmedSha256.matches(Regex("[0-9a-f]{64}")))
        validateUri(input.candidate.uri)
        currentCoroutineContext().ensureActive()
        verifyCandidate(input.source, input.candidate)
        val copy = copies.create(input.candidate, confirmedSha256)
        try {
            currentCoroutineContext().ensureActive()
            verifyCandidate(input.source, input.candidate)
            val source = LocalSourceSpec(input.source.sourceId, LocalSourceKind.DOCUMENT, copy.uri)
            val observed = sources.observe(source)
            validateObservation(source, observed.source, observed.root.directory, observed.root.sizeBytes)
            currentCoroutineContext().ensureActive()
            val association = LyricsAssociation(observed.root.objectId, source.sourceId, copy.uri, 0,
                input.encoding, copy.sha256)
            return PreparedLyricsAssociation(association, LyricsDocumentInput(
                association.documentId, input.sourceGeneration, observed.source, observed.root,
                association.encoding, copy.sha256, requirePersistedRead = false,
            ), copy::release)
        } catch (error: Exception) {
            copy.release()
            throw error
        } catch (error: OutOfMemoryError) {
            copy.release()
            throw error
        }
    }

    private fun validateObservation(expected: LocalSourceSpec, actual: LocalSourceSpec, directory: Boolean, bytes: Long?) {
        if (actual.copy(authority = expected.authority, rootObjectId = expected.rootObjectId) != expected ||
            expected.authority != null && actual.authority != expected.authority ||
            expected.rootObjectId != null && actual.rootObjectId != expected.rootObjectId) fail(LyricsLoadIssue.SOURCE_CHANGED)
        if (directory) fail(LyricsLoadIssue.INVALID_INPUT)
        if (bytes != null && bytes > LyricsLimits.INPUT_BYTES) fail(LyricsLoadIssue.INPUT_TOO_LARGE)
    }

    private fun validateUri(value: String): Uri {
        if (value.isBlank() || value.length > LocalInputLimits.URI_CHARACTERS || value.any(Char::isISOControl)) {
            fail(LyricsLoadIssue.INVALID_INPUT)
        }
        val uri = try {
            AndroidLocalSources.contentUri(value)
        } catch (error: SourceReadException) {
            if (error.issue != SourceReadIssue.INVALID_SOURCE) throw error
            fail(LyricsLoadIssue.INVALID_INPUT)
        }
        if (!uri.isHierarchical || uri.authority.isNullOrBlank() || uri.query != null || uri.fragment != null) {
            fail(LyricsLoadIssue.INVALID_INPUT)
        }
        return uri
    }

    private fun fail(issue: LyricsLoadIssue): Nothing = throw LyricsDocumentResolutionException(issue)
}

internal fun SourceReadException.lyricsResolutionIssue(): LyricsLoadIssue = when (issue) {
    SourceReadIssue.BUSY -> LyricsLoadIssue.BUSY
    SourceReadIssue.PERMISSION_DENIED -> LyricsLoadIssue.PERMISSION_DENIED
    SourceReadIssue.PERMISSION_REVOKED -> LyricsLoadIssue.PERMISSION_REVOKED
    SourceReadIssue.SOURCE_CHANGED -> LyricsLoadIssue.SOURCE_CHANGED
    SourceReadIssue.CONTENT_UNKNOWN -> LyricsLoadIssue.CONTENT_UNKNOWN
    SourceReadIssue.FILE_MISSING -> LyricsLoadIssue.FILE_MISSING
    SourceReadIssue.INVALID_SOURCE, SourceReadIssue.NOT_AUDIO -> LyricsLoadIssue.INVALID_INPUT
    SourceReadIssue.SOURCE_UNAVAILABLE, SourceReadIssue.METADATA_UNREADABLE, SourceReadIssue.UNSUPPORTED_FORMAT,
    SourceReadIssue.STORAGE_FULL -> LyricsLoadIssue.SOURCE_UNAVAILABLE
}
