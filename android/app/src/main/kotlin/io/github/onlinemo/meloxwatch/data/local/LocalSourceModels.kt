package io.github.onlinemo.meloxwatch.data.local

enum class LocalSourceKind { MEDIA_STORE, DOCUMENT, TREE, APP_OWNED }

/** URI is an access route. sourceId and provider objectId have separate lifetimes. */
data class LocalSourceSpec(
    val sourceId: String,
    val kind: LocalSourceKind,
    val uri: String,
    val grantFlags: Int = 0,
    val mediaVolume: String = "external",
    val authority: String? = null,
    val rootObjectId: String? = null,
    val providerIncarnation: String? = null,
) {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= 128)
        require(uri.isNotBlank() && uri.length <= LocalInputLimits.URI_CHARACTERS)
        require(mediaVolume.isNotBlank() && mediaVolume.length <= 256)
    }
}

enum class ObservedCapability { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** A declared document flag never claims that a write/delete was actually performed. */
data class LocalGrantObservation(
    val read: ObservedCapability = ObservedCapability.UNKNOWN,
    val enumerate: ObservedCapability = ObservedCapability.UNKNOWN,
    val persistedRead: ObservedCapability = ObservedCapability.UNKNOWN,
    val persistedWrite: ObservedCapability = ObservedCapability.UNKNOWN,
    val offlineRead: ObservedCapability = ObservedCapability.UNKNOWN,
    val write: ObservedCapability = ObservedCapability.UNKNOWN,
    val delete: ObservedCapability = ObservedCapability.UNKNOWN,
    val rename: ObservedCapability = ObservedCapability.UNKNOWN,
    val move: ObservedCapability = ObservedCapability.UNKNOWN,
    val declaredWrite: Boolean? = null,
    val declaredDelete: Boolean? = null,
    val declaredRename: Boolean? = null,
    val declaredMove: Boolean? = null,
    val appOwned: Boolean = false,
    val returnedFlags: Int = 0,
    val acceptedPersistedFlags: Int = 0,
    val persistenceAttempted: Boolean = false,
    val persistenceRejected: Boolean = false,
)

enum class SourceReadIssue {
    BUSY,
    PERMISSION_DENIED,
    PERMISSION_REVOKED,
    SOURCE_UNAVAILABLE,
    SOURCE_CHANGED,
    CONTENT_UNKNOWN,
    INVALID_SOURCE,
    NOT_AUDIO,
    METADATA_UNREADABLE,
    FILE_MISSING,
    UNSUPPORTED_FORMAT,
    STORAGE_FULL,
}

class SourceReadException(val issue: SourceReadIssue, cause: Throwable? = null) :
    Exception(issue.name, cause)

enum class LocalNodeKind { AUDIO, DIRECTORY, OTHER }

/** parentObjectId is a proven enumeration edge, never a split display name. */
data class ObservedCandidate(
    val sourceId: String,
    val objectId: String,
    val uri: String,
    val parentObjectId: String?,
    val displayName: String,
    val mimeType: String?,
    val kind: LocalNodeKind,
    val sizeBytes: Long? = null,
    val modifiedEpochMs: Long? = null,
    val providerVersion: String? = null,
    val addedEpochSeconds: Long? = null,
    val generationAdded: Long? = null,
    val generationModified: Long? = null,
    val providerContentToken: String? = null,
    val entityProof: String? = null,
) {
    init {
        require(objectId.isNotBlank() && objectId.length <= LocalInputLimits.URI_CHARACTERS)
        require(uri.isNotBlank() && uri.length <= LocalInputLimits.URI_CHARACTERS)
        require(sizeBytes == null || sizeBytes >= 0)
    }

    val directory: Boolean get() = kind == LocalNodeKind.DIRECTORY

    val mayContainAudio: Boolean
        get() = kind == LocalNodeKind.AUDIO || (!directory && (
            mimeType?.startsWith("audio/", ignoreCase = true) == true ||
                displayName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in AUDIO_EXTENSIONS
            ))

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "aiff", "aif", "ape", "dsf", "dff",
        )
    }
}

data class LocalSourceObservation(
    val source: LocalSourceSpec,
    val identity: String,
    val access: LocalGrantObservation,
    val root: ObservedCandidate,
    val providerVersion: String? = null,
    val mediaGeneration: Long? = null,
)

object LocalInputLimits {
    const val BATCH_SIZE = 100
    const val URI_CHARACTERS = 8192
    const val NAME_CHARACTERS = 512
    const val TAG_CHARACTERS = 4096
    const val TAG_VALUES = 128
    const val READ_PROOF_BYTES = 4 * 1024 * 1024
}
