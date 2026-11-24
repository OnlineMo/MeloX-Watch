package io.github.onlinemo.meloxwatch.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Stable classified identities; names from scans and user aliases occupy different tables. */
@Entity(tableName = "display_groups", indices = [
    Index(value = ["kind", "groupKey"], unique = true, name = "index_display_groups_kind_groupKey"),
])
data class DisplayGroupEntity(
    @PrimaryKey val entityId: String,
    val kind: String,
    val groupKey: String,
    val originalName: String?,
    val originalSubtitle: String?,
    val originalSortKey: String,
    val originalSectionKey: String,
    val originalSearchName: String,
    val editedFrom: Long? = null,
)

@Entity(tableName = "display_names", foreignKeys = [
    ForeignKey(entity = DisplayGroupEntity::class, parentColumns = ["entityId"],
        childColumns = ["groupId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["groupId", "validFrom"], unique = true, name = "index_display_names_groupId_validFrom"),
    Index(value = ["validTo"], name = "index_display_names_validTo"),
])
data class DisplayNameEntity(
    @PrimaryKey val versionId: String,
    val groupId: String,
    val displayName: String,
    val sortKey: String,
    val sectionKey: String,
    val searchName: String,
    val validFrom: Long,
    val validTo: Long?,
)

/** An album patch assigns one display album per track without rewriting the scanned album. */
@Entity(tableName = "display_album_links", foreignKeys = [
    ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["trackId"],
        childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = DisplayGroupEntity::class, parentColumns = ["entityId"],
        childColumns = ["groupId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["trackId", "validFrom"], unique = true, name = "index_display_album_links_trackId_validFrom"),
    Index(value = ["groupId", "validFrom"], name = "index_display_album_links_groupId_validFrom"),
    Index(value = ["validTo"], name = "index_display_album_links_validTo"),
])
data class DisplayAlbumLinkEntity(
    @PrimaryKey val versionId: String,
    val trackId: String,
    val groupId: String,
    val validFrom: Long,
    val validTo: Long?,
)

/** Each artist patch changes only one relationship; other original or edited artists survive. */
@Entity(tableName = "display_artist_links", foreignKeys = [
    ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["trackId"],
        childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = DisplayGroupEntity::class, parentColumns = ["entityId"],
        childColumns = ["groupId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["trackId", "groupId", "validFrom"], unique = true,
        name = "index_display_artist_links_trackId_groupId_validFrom"),
    Index(value = ["groupId", "validFrom"], name = "index_display_artist_links_groupId_validFrom"),
    Index(value = ["validTo"], name = "index_display_artist_links_validTo"),
])
data class DisplayArtistLinkEntity(
    @PrimaryKey val versionId: String,
    val trackId: String,
    val groupId: String,
    val included: Boolean,
    val validFrom: Long,
    val validTo: Long?,
)
