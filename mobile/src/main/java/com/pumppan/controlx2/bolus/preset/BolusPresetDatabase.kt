package com.pumppan.controlx2.bolus.preset

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pumppan.controlx2.bolus.state.BolusLimits

/**
 * 预设独立库：不塞进上游 :db 模块（:db 是上游同步/历史库，预设是 pumppan 本地功能）。
 *
 * v2：只存绝对值（立即段 / 延后段 / 分钟）。v1 → v2 迁移按当前型号的输注步进取整，
 * 因此建库时把 [BolusLimits.bolusIncrementMilliunits] 传进来（只在首次打开时用到）。
 */
@Database(
    entities = [BolusPresetEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class BolusPresetDatabase : RoomDatabase() {
    abstract fun bolusPresetDao(): BolusPresetDao

    companion object {
        private const val DB_NAME = "bolus_presets.db"

        @Volatile
        private var INSTANCE: BolusPresetDatabase? = null

        fun getDatabase(
            context: Context,
            incrementMilliunits: Int = BolusLimits.BOLUS_INCREMENT_MILLIUNITS,
        ): BolusPresetDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BolusPresetDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(bolusPresetMigration1To2(incrementMilliunits))
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
