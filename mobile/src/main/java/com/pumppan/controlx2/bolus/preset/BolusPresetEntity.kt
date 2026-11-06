package com.pumppan.controlx2.bolus.preset

import androidx.room.Entity
import androidx.room.PrimaryKey

const val BolusPresetTable = "bolus_presets"

/** Room 表 [BolusPresetTable]；v2 起只存绝对值（立即段 / 延后段 / 分钟）。 */
@Entity(tableName = BolusPresetTable)
data class BolusPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sortOrder: Int,
    val immediateMilliunits: Int,
    val laterMilliunits: Int,
    val durationMinutes: Int,
) {
    fun toPreset(): BolusPreset = BolusPreset(
        id = id,
        name = name,
        sortOrder = sortOrder,
        immediateMilliunits = immediateMilliunits,
        laterMilliunits = laterMilliunits,
        durationMinutes = durationMinutes,
    )
}

fun BolusPreset.toEntity(): BolusPresetEntity = BolusPresetEntity(
    id = id,
    name = name,
    sortOrder = sortOrder,
    immediateMilliunits = immediateMilliunits,
    laterMilliunits = laterMilliunits,
    durationMinutes = durationMinutes,
)
