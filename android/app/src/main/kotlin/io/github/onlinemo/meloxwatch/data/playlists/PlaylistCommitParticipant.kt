package io.github.onlinemo.meloxwatch.data.playlists

/**
 * Staging rechecks authorization between bounded batches. The remaining methods participate
 * in the same writer transaction as the head CAS and playlist receipt. They must not perform
 * file/network work or reenter a repository mutex. Replay verifies an already durable result.
 */
internal interface PlaylistCommitParticipant {
    fun checkActive()
    suspend fun verifyReplay(commit: PlaylistCommit)
    suspend fun claimCommit()
    suspend fun recordCommit(commit: PlaylistCommit)
}
