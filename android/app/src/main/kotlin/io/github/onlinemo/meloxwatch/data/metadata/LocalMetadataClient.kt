package io.github.onlinemo.meloxwatch.data.metadata

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits

/** Only these reviewed fields can form a request; local descriptors and filenames are absent. */
internal class MetadataSearchQuery private constructor(val title: String?, val artist: String?, val words: String) {
    override fun toString(): String = "MetadataSearchQuery(redacted)"

    companion object {
        fun reviewed(title: String, artist: String, sendTitle: Boolean, sendArtist: Boolean): MetadataSearchQuery {
            val selectedTitle = title.trim().takeIf { sendTitle && it.isNotEmpty() }
            val selectedArtist = artist.trim().takeIf { sendArtist && it.isNotEmpty() }
            if (selectedTitle == null && selectedArtist == null ||
                !validField(selectedTitle, 256) || !validField(selectedArtist, 512)) invalid()
            val words = listOfNotNull(selectedTitle, selectedArtist).joinToString(" ")
            if (words.length > 512) invalid()
            return MetadataSearchQuery(selectedTitle, selectedArtist, words)
        }

        private fun validField(value: String?, limit: Int): Boolean = value == null ||
            value.length <= limit && value.none(Char::isISOControl) &&
                !value.contains('\\') && !value.startsWith('/') && !SCHEME.containsMatchIn(value) &&
                !ADDRESS.containsMatchIn(value) && !EMAIL.containsMatchIn(value) &&
                !OPAQUE_ID.containsMatchIn(value) && !FILE_PATH.containsMatchIn(value)

        private val SCHEME = Regex("(?i)(?:[a-z][a-z0-9+.-]*://|(?:content|file|mailto):|[a-z]:[/\\\\])")
        private val ADDRESS = Regex("(?:\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b|(?<![0-9A-Za-z])(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}(?![0-9A-Za-z]))")
        private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        private val OPAQUE_ID = Regex("(?i)(?:\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b|\\b[0-9a-f]{64}\\b)")
        private val FILE_PATH = Regex("[^\\s/]+/[^\\s/]+/[^\\s/]+")
        private fun invalid(): Nothing = throw MetadataStoreException(MetadataStoreIssue.INVALID_INPUT)
    }
}

internal class MetadataSearchPage(val candidates: List<MetadataProvenance>, val rawCount: Int, val more: Boolean) {
    init {
        require(candidates.size <= 20 && rawCount == candidates.size && rawCount in 0..20 &&
            candidates.map { it.songId }.distinct().size == candidates.size && (!more || rawCount > 0))
    }
    override fun toString(): String = "MetadataSearchPage(redacted)"
}

internal class MetadataArtifact(val bytes: ByteArray, val provenance: MetadataProvenance) {
    override fun toString(): String = "MetadataArtifact(redacted)"
}

/** Public metadata has an independent anonymous transport; it never opens an account or audio. */
internal interface LocalMetadataClient {
    suspend fun search(query: MetadataSearchQuery, offset: Long): MetadataSearchPage
    suspend fun lyrics(candidate: MetadataProvenance, maxAccountedBytes: Long = LyricsLimits.ACCOUNTED_BYTES): MetadataArtifact
    suspend fun artwork(candidate: MetadataProvenance): MetadataArtifact
}
