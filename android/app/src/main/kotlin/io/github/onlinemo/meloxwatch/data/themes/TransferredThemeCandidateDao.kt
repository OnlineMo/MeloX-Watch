package io.github.onlinemo.meloxwatch.data.themes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TransferredThemeCandidateDao {
    @Query("SELECT * FROM transferred_theme_candidates WHERE operationId = :operationId")
    suspend fun candidate(operationId: String): TransferredThemeCandidateEntity?

    @Query("SELECT * FROM transferred_theme_candidates WHERE state IN ('RECEIVED', 'APPLYING') ORDER BY operationId LIMIT 2")
    suspend fun unresolved(): List<TransferredThemeCandidateEntity>

    @Query("SELECT * FROM transferred_theme_candidates WHERE (:afterOperationId IS NULL OR operationId > :afterOperationId) ORDER BY operationId LIMIT :limit")
    suspend fun page(afterOperationId: String?, limit: Int): List<TransferredThemeCandidateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(candidate: TransferredThemeCandidateEntity)

    @Update
    suspend fun update(candidate: TransferredThemeCandidateEntity): Int
}
