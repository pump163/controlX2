package com.pumppan.controlx2.bolus.preset

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 预设独立库：不塞进上游 :db 模块（:db 是上游同步/历史库，预设是 pumppan 本地功能）。
 * v1 无 Migration；删库/降版本即可回退。
 */
@Database(
    entities = [BolusPresetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BolusPresetDatabase : RoomDatabase() {
    abstract fun bolusPresetDao(): BolusPresetDao

    companion object {
        private const val DB_NAME = "bolus_presets.db"

        @Volatile
        private var INSTANCE: BolusPresetDatabase? = null

        fun getDatabase(context: Context): BolusPresetDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BolusPresetDatabase::class.java,
                    DB_NAME,
                ).build().also { INSTANCE = it }
            }
    }
}
