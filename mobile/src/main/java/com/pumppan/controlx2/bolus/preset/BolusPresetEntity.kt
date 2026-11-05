package com.pumppan.controlx2.bolus.preset

import androidx.room.Entity
import androidx.room.PrimaryKey

const val BolusPresetTable = "bolus_presets"

/** Room 表 [BolusPresetTable]；v1 无 Migration（独立新库）。 */
@Entity(tableName = BolusPresetTable)
data class BolusPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sortOrder: Int,
    val totalMilliunits: Int,
    val extendedEnabled: Boolean,
    val nowPercent: Int,
    val extendedSeconds: Int,
) {
    fun toPreset(): BolusPreset = BolusPreset(
        id = id,
        name = name,
        sortOrder = sortOrder,
        totalMilliunits = totalMilliunits,
        extendedEnabled = extendedEnabled,
        nowPercent = nowPercent,
        extendedSeconds = extendedSeconds,
    )
}

fun BolusPreset.toEntity(): BolusPresetEntity = BolusPresetEntity(
    id = id,
    name = name,
    sortOrder = sortOrder,
    totalMilliunits = totalMilliunits,
    extendedEnabled = extendedEnabled,
    nowPercent = nowPercent,
    extendedSeconds = extendedSeconds,
)
