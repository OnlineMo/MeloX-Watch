package io.github.onlinemo.meloxwatch.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Separate DAO keeps queries for this new table out of historical V3/V4 schema declarations. */
@Dao
interface TransferredImportDao {
    @Query("SELECT * FROM transferred_import_operations WHERE operationId = :id")
    suspend fun transferredImportRecord(id: String): LibraryTransferredImportEntity?
    @Query("SELECT * FROM transferred_import_operations WHERE ownedSourceId = :sourceId AND phase = 'COMMITTED'")
    suspend fun ownedTransferredImport(sourceId: String): LibraryTransferredImportEntity?
    @Insert suspend fun insertTransferredImport(value: LibraryTransferredImportEntity)
    @Update suspend fun updateTransferredImport(value: LibraryTransferredImportEntity)
    @Query("SELECT * FROM transferred_import_operations WHERE phase IN ('COPYING', 'VERIFIED', 'INSTALLED') " +
        "AND (:after IS NULL OR operationId > :after) ORDER BY operationId LIMIT :limit")
    suspend fun pendingTransferredImports(after: String?, limit: Int): List<LibraryTransferredImportEntity>
}
