package com.voiceguard.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CallSession::class,
        WindowScore::class,
        AlertRecord::class,
        ScamKeyword::class,
        KeywordHitRecord::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun windows(): WindowDao
    abstract fun alerts(): AlertDao
    abstract fun keywordHits(): KeywordHitDao
    abstract fun keywords(): KeywordDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "voiceguard.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
