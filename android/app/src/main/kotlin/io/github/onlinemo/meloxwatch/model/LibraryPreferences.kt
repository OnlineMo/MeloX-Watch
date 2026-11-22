package io.github.onlinemo.meloxwatch.model

enum class LibrarySortField {
    TITLE, ARTIST, ALBUM, DURATION, ADDED, FILENAME_NATURAL, ALBUM_TRACK, PLAY_COUNT, LISTENING_TIME,
}

data class LibrarySort(val field: LibrarySortField = LibrarySortField.TITLE, val descending: Boolean = false)

enum class LibraryListContext { ALL, SEARCH, ALBUM, ARTIST, FOLDER, RECENT, HISTORY }
enum class LibraryLayout { LIST, GRID }
enum class ArtworkReadPath { SYSTEM_THUMBNAIL, LOCAL_EXTRACT }

fun defaultLibrarySorts(): Map<LibraryListContext, LibrarySort> = LibraryListContext.entries.associateWith { context ->
    when (context) {
        LibraryListContext.ALBUM -> LibrarySort(LibrarySortField.ALBUM_TRACK)
        LibraryListContext.RECENT -> LibrarySort(LibrarySortField.ADDED, descending = true)
        else -> LibrarySort()
    }
}
