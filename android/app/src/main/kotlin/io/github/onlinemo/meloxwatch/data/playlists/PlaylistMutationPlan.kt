package io.github.onlinemo.meloxwatch.data.playlists

/** 只计算密集序号变换，不读取source或修改存储。 */
internal data class PlaylistMutationPlan(
    val old: PlaylistGenerationEntity?,
    val name: String,
    val nameKey: String,
    val result: PlaylistOperationResult,
    val input: FrozenPlaylistInput? = null,
    val removeMemberId: String? = null,
    val removeOrdinal: Long? = null,
    val moveFrom: Long? = null,
    val moveTo: Long? = null,
    val appendFavorite: PlaylistReference? = null,
    val folderSynchronization: PlaylistFolderSynchronization? = null,
    val replaceMembers: Boolean = false,
    val replacementMemberId: String? = null,
    val replacementReference: PlaylistReference? = null,
    val shareData: Boolean = false,
    val unchanged: Boolean = false,
) {
    fun ordinalAfter(oldOrdinal: Long): Long {
        val removed = removeOrdinal
        if (removed != null) return if (oldOrdinal > removed) oldOrdinal - 1L else oldOrdinal
        val from = moveFrom ?: return oldOrdinal
        val to = checkNotNull(moveTo)
        return when {
            oldOrdinal == from -> to
            from < to && oldOrdinal > from && oldOrdinal <= to -> oldOrdinal - 1L
            from > to && oldOrdinal >= to && oldOrdinal < from -> oldOrdinal + 1L
            else -> oldOrdinal
        }
    }
}

internal fun PlaylistMutationRequest.fingerprint(): String {
    val fields = mutableListOf<String?>("playlist-operation-v1", playlistId, expectedRevision.toString())
    fun input(view: FrozenPlaylistInput?) {
        fields.add(view?.id)
        fields.add(view?.revision?.toString())
        fields.add(view?.totalCount?.toString())
    }
    fun reference(value: PlaylistReference) {
        fields.add(value.identityKey())
        fields.add(value.issue?.name)
        fields.add(value.titleHint)
        fields.add(value.artistHint)
        fields.add(value.albumHint)
        fields.add(value.relativePathHint)
        fields.add(value.descriptionHint)
        fields.add(value.durationMs?.toString())
    }
    fun sourceFence(value: PlaylistCatalogFence) {
        fields.add(value.catalogRevision.toString())
        fields.add(value.rulesRevision.toString())
        fields.add(value.sourceId)
        fields.add(value.sourceRevision.toString())
        fields.add(value.authEpoch.toString())
        fields.add(value.scanId)
    }
    fun folder(value: PlaylistFolderSynchronization) {
        fields.add(value.binding.sourceId)
        fields.add(value.binding.nodeId)
        fields.add(value.binding.subtree.toString())
        sourceFence(value.fence)
        input(value.input)
    }
    when (val edit = mutation) {
        is PlaylistMutation.Create -> {
            fields.add("create")
            fields.add(playlistName(edit.name))
            input(edit.input)
        }
        is PlaylistMutation.CreateFolder -> {
            fields.add("create-folder")
            fields.add(playlistName(edit.name))
            folder(edit.synchronization)
        }
        is PlaylistMutation.Rename -> {
            fields.add("rename")
            fields.add(playlistName(edit.name))
        }
        is PlaylistMutation.Delete -> {
            fields.add("delete")
            fields.add(edit.confirmed.toString())
        }
        is PlaylistMutation.Add -> {
            fields.add("add")
            input(edit.input)
        }
        is PlaylistMutation.Remove -> {
            fields.add("remove")
            fields.add(edit.memberId)
            fields.add(edit.confirmed.toString())
        }
        is PlaylistMutation.Move -> {
            fields.add("move")
            fields.add(edit.memberId)
            fields.add(edit.destinationOrdinal.toString())
        }
        is PlaylistMutation.SetFavorite -> {
            fields.add("favorite")
            fields.add(edit.enabled.toString())
            fields.add(edit.reference.identityKey())
            fields.add(edit.reference.titleHint)
            fields.add(edit.reference.artistHint)
            fields.add(edit.reference.albumHint)
            fields.add(edit.reference.relativePathHint)
            fields.add(edit.reference.descriptionHint)
            fields.add(edit.reference.durationMs?.toString())
        }
        is PlaylistMutation.SynchronizeFolder -> {
            fields.add("synchronize-folder")
            folder(edit.synchronization)
        }
        is PlaylistMutation.RebindFolder -> {
            fields.add("rebind-folder")
            fields.add(edit.confirmed.toString())
            folder(edit.synchronization)
        }
        is PlaylistMutation.ReplaceReference -> {
            fields.add("replace-reference")
            fields.add(edit.memberId)
            fields.add(edit.confirmed.toString())
            reference(edit.reference)
        }
    }
    // 没有新增围栏时保持已保存v1 receipt的字节语义；新增版本明确进入操作指纹。
    expectedLibraryRevision?.let {
        fields.add("library-revision-v1")
        fields.add(it.catalogRevision.toString())
        fields.add(it.rulesRevision.toString())
    }
    expectedSourceFence?.let {
        fields.add("source-fence-v1")
        sourceFence(it)
    }
    return playlistDigest(fields)
}

internal fun PlaylistMutation.folderSynchronization(): PlaylistFolderSynchronization? = when (this) {
    is PlaylistMutation.CreateFolder -> synchronization
    is PlaylistMutation.SynchronizeFolder -> synchronization
    is PlaylistMutation.RebindFolder -> synchronization
    else -> null
}

internal fun PlaylistMutation.frozenInput(): FrozenPlaylistInput? = when (this) {
    is PlaylistMutation.Create -> input
    is PlaylistMutation.Add -> input
    else -> folderSynchronization()?.input
}
