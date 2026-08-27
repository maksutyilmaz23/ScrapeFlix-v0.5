package com.scrapeflix.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SiteEntity::class, ScrapedItemEntity::class, DownloadEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteDao(): SiteDao
    abstract fun itemDao(): ItemDao
    abstract fun downloadDao(): DownloadDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "scrapeflix.db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
