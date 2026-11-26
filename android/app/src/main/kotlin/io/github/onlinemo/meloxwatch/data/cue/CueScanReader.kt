package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface CueLocalDocumentAccess {
    suspend fun parse(document: CueDocumentAccess, candidate: ObservedCandidate, encoding: CueEncoding): ParsedCue
    suspend fun validate(descriptor: LocalReadDescriptor): LocalReadDescriptor
    suspend fun inspectWave(descriptor: LocalReadDescriptor): CuePcmWaveFacts
    suspend fun proveWave(descriptor: LocalReadDescriptor): CueWaveProof {
        val before = validate(descriptor)
        val facts = inspectWave(before)
        val after = validate(before)
        if (after != before) throw CueException(CueIssue.SOURCE_CHANGED)
        return CueWaveProof(after, facts)
    }
}

/** 在 Room 外准备一个有界完整组；取消时不得发布部分结果。 */
class CueScanReader(
    private val store: CueScanStore,
    private val matcher: CuePathMatcher,
    private val documents: CueLocalDocumentAccess,
) {
    suspend fun read(token: ScanToken, candidate: ObservedCandidate, encoding: CueEncoding = CueEncoding.UTF8): CueGroupCommit {
        currentCoroutineContext().ensureActive()
        val context = store.cueScanContext(token, candidate)
        val parsed = documents.parse(context.document, context.scope.cue, encoding)
        if (parsed.files.any { it.declaredType != CueFileType.WAVE }) throw CueException(CueIssue.INVALID_AUDIO)
        val cueRead = documents.validate(context.document.readDescriptor(parsed.rawContentToken))
        requireVerified(cueRead)
        val matched = matcher.match(parsed, context.scope)
        val pcm = LinkedHashMap<Int, CuePcmWaveFacts>()
        val audio = ArrayList<CueAudioFacts>(parsed.files.size)
        for (file in matched.files) {
            currentCoroutineContext().ensureActive()
            val descriptor = store.cueAudioDescriptor(token, file.candidate)
            val proof = documents.proveWave(descriptor)
            requireVerified(proof.descriptor)
            pcm[file.fileIndex] = proof.pcm
            audio += CueAudioFacts(file.fileIndex, proof.descriptor, proof.pcm.durationUs)
        }
        // 全部音频读取完成后重新打开文档；CUE 已变化时不得发布旧边界。
        val final = documents.parse(context.document, matched.scope.cue, encoding)
        if (final.rawContentToken != parsed.rawContentToken) throw CueException(CueIssue.SOURCE_CHANGED)
        val document = CueDocumentSnapshot(context.document.identity, context.document.source, context.document.objectId,
            context.document.location, parsed.rawContentToken, encoding)
        val coroutine = currentCoroutineContext()
        val prepared = CueGroupValidator.prepare(parsed, document, matched, audio, coroutine::ensureActive)
        coroutine.ensureActive()
        return store.commitCueGroup(token, prepared, pcm.toMap())
    }

    private fun requireVerified(descriptor: LocalReadDescriptor) {
        if (descriptor.contentValidation != LocalContentValidation.VERIFIED) throw CueException(CueIssue.UNVERIFIED_CONTENT)
    }
}

/** 扫描器、解析器与元数据读取器共用既有的单一物理提供方通道。 */
class AndroidCueLocalDocuments(
    private val sources: AndroidLocalSources,
) : CueLocalDocumentAccess {

    override suspend fun parse(document: CueDocumentAccess, candidate: ObservedCandidate, encoding: CueEncoding): ParsedCue {
        currentCoroutineContext().ensureActive()
        if (candidate.sourceId != document.source.spec.sourceId || candidate.objectId != document.objectId ||
            candidate.uri != document.location.uri || candidate.directory) throw CueException(CueIssue.SOURCE_CHANGED)
        val parsed = sources.parseCue(document.readDescriptor(), encoding, candidate)
        currentCoroutineContext().ensureActive()
        return parsed
    }

    override suspend fun validate(descriptor: LocalReadDescriptor): LocalReadDescriptor = sources.validateCueRead(descriptor)
    override suspend fun proveWave(descriptor: LocalReadDescriptor): CueWaveProof = sources.proveCueWave(descriptor)

    override suspend fun inspectWave(descriptor: LocalReadDescriptor): CuePcmWaveFacts {
        if (descriptor.contentValidation != LocalContentValidation.VERIFIED) throw CueException(CueIssue.UNVERIFIED_CONTENT)
        val proof = sources.proveCueWave(descriptor)
        if (proof.descriptor.verifiedContentToken != descriptor.verifiedContentToken) throw CueException(CueIssue.SOURCE_CHANGED)
        return proof.pcm
    }
}
