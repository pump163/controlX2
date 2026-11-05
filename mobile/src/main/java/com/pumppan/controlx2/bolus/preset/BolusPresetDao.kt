package com.pumppan.controlx2.bolus.preset

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface BolusPresetDao {
    /** 固定顺序：只按用户可调的 sortOrder；不按最近使用、不按创建时间。 */
    @Query("SELECT * FROM $BolusPresetTable ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<BolusPresetEntity>

    @Query("SELECT * FROM $BolusPresetTable WHERE id = :id")
    suspend fun getById(id: Long): BolusPresetEntity?

    @Query("SELECT COUNT(*) FROM $BolusPresetTable")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: BolusPresetEntity): Long

    @Update
    suspend fun update(entity: BolusPresetEntity)

    @Query("DELETE FROM $BolusPresetTable WHERE id = :id")
    suspend fun deleteById(id: Long)
}
