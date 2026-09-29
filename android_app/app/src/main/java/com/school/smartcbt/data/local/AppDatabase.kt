package com.school.smartcbt.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [QuestionEntity::class, PendingAttendanceEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cbtDao(): CbtDao
    abstract fun pendingAttendanceDao(): PendingAttendanceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_cbt_local_db"
                ).fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
