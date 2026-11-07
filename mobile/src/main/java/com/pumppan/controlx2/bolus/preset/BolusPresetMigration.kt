package com.pumppan.controlx2.bolus.preset

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pumppan.controlx2.bolus.state.BolusForm

/** v1 行换算后的绝对值形态。 */
data class LegacyPresetValues(
    val immediateMilliunits: Int,
    val laterMilliunits: Int,
    val durationMinutes: Int,
)

/**
 * v1 → v2 换算：旧的「总量 + 立即占比」改成「立即段 / 延后段 / 分钟」。
 *
 * - 非延展：全部进立即段，延后段与时长归 0。
 * - 延展：`later = roundToIncrement(total × (100 − nowPercent) / 100)`，`immediate = total − later`。
 *   **nowPercent 是立即段占比**，所以延后段用 `100 − nowPercent`；不能直接用 `nowPercent`，
 *   否则立即/延后两段会被对调（剂量正确性事故）。
 * - 取整按调用方当前的输注步进（X2 50 mU / Mobi 10 mU）；延后段落在步进网格上后，
 *   立即段 = 总量 − 延后段，总量本身在网格上则两段都在网格上。
 * - 时长由秒换算成分钟（UI 与 v2 模型都按分钟）；保留 name / sortOrder / id。
 */
fun convertLegacyPreset(
    totalMilliunits: Int,
    extendedEnabled: Boolean,
    nowPercent: Int,
    extendedSeconds: Int,
    incrementMilliunits: Int,
): LegacyPresetValues {
    val total = totalMilliunits.coerceAtLeast(0)
    if (!extendedEnabled) {
        return LegacyPresetValues(immediateMilliunits = total, laterMilliunits = 0, durationMinutes = 0)
    }
    val percent = nowPercent.coerceIn(0, 100)
    // 复用输入页同一条换算（延后段 half-up 到步进，立即段 = 总量 − 延后段）。
    val split = BolusForm.splitByPercent(total, percent, incrementMilliunits)
    return LegacyPresetValues(
        immediateMilliunits = split.nowMilliunits,
        laterMilliunits = split.laterMilliunits,
        durationMinutes = (extendedSeconds / 60).coerceAtLeast(0),
    )
}

/** Room 迁移：重建表（旧列 percent / total 不能留在 v2 schema 里）。 */
fun bolusPresetMigration1To2(incrementMilliunits: Int): Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$BolusPresetTable}_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`immediateMilliunits` INTEGER NOT NULL, " +
                "`laterMilliunits` INTEGER NOT NULL, " +
                "`durationMinutes` INTEGER NOT NULL)",
        )
        db.query(
            "SELECT id, name, sortOrder, totalMilliunits, extendedEnabled, nowPercent, extendedSeconds " +
                "FROM `$BolusPresetTable` ORDER BY sortOrder ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val converted = convertLegacyPreset(
                    totalMilliunits = cursor.getInt(3),
                    extendedEnabled = cursor.getInt(4) != 0,
                    nowPercent = cursor.getInt(5),
                    extendedSeconds = cursor.getInt(6),
                    incrementMilliunits = incrementMilliunits,
                )
                db.execSQL(
                    "INSERT INTO `$BolusPresetTable}_new` " +
                        "(id, name, sortOrder, immediateMilliunits, laterMilliunits, durationMinutes) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        converted.immediateMilliunits,
                        converted.laterMilliunits,
                        converted.durationMinutes,
                    ),
                )
            }
        }
        db.execSQL("DROP TABLE `$BolusPresetTable`")
        db.execSQL("ALTER TABLE `$BolusPresetTable}_new` RENAME TO `$BolusPresetTable`")
    }
}
