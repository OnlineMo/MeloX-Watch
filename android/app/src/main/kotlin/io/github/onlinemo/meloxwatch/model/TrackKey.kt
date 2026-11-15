package io.github.onlinemo.meloxwatch.model

import java.util.UUID

/** 曲目身份不由标题或 URI 推导；位置重新关联时保留这个 key。 */
data class TrackKey(val providerId: String, val trackId: String) {
    init {
        require(providerId.isNotBlank())
        require(trackId.isNotBlank())
    }

    companion object {
        const val LOCAL_PROVIDER = "local"

        fun newLocal(): TrackKey = TrackKey(LOCAL_PROVIDER, UUID.randomUUID().toString())
    }
}
