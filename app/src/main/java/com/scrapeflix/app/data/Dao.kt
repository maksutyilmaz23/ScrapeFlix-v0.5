package com.scrapeflix.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {
    @Query("SELECT * FROM site_profiles ORDER BY name COLLATE NOCASE")
    fun observeSites(): Flow<List<SiteEntity>>
    @Insert suspend fun insert(site: SiteEntity): Long
    @Update suspend fun update(site: SiteEntity)
    @Delete suspend fun delete(site: SiteEntity)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM scraped_items WHERE siteId = :siteId ORDER BY title COLLATE NOCASE")
    fun observeForSite(siteId: Long): Flow<List<ScrapedItemEntity>>
    @Query("SELECT * FROM scraped_items ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<ScrapedItemEntity>>
    @Insert suspend fun insertAll(items: List<ScrapedItemEntity>)
    @Query("DELETE FROM scraped_items WHERE siteId = :siteId") suspend fun deleteForSite(siteId: Long)
}
