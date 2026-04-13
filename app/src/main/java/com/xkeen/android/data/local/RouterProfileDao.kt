package com.xkeen.android.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RouterProfileDao {
    @Query("SELECT * FROM router_profiles ORDER BY alias ASC")
    fun getAll(): Flow<List<RouterProfileEntity>>

    @Query("SELECT * FROM router_profiles WHERE id = :id")
    suspend fun getById(id: Long): RouterProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: RouterProfileEntity): Long

    @Update
    suspend fun update(profile: RouterProfileEntity)

    @Delete
    suspend fun delete(profile: RouterProfileEntity)
}
