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
    @Query("SELECT * FROM scraped_items WHERE siteId = :siteId ORDER BY category COLLATE NOCASE, sortOrder")
    fun observeForSite(siteId: Long): Flow<List<ScrapedItemEntity>>
    @Query("SELECT * FROM scraped_items ORDER BY category COLLATE NOCASE, sortOrder")
    fun observeAll(): Flow<List<ScrapedItemEntity>>
    @Query("SELECT * FROM scraped_items WHERE isFavorite = 1 ORDER BY category COLLATE NOCASE, sortOrder")
    fun observeFavorites(): Flow<List<ScrapedItemEntity>>
    @Query("UPDATE scraped_items SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)
    @Insert suspend fun insertAll(items: List<ScrapedItemEntity>)
    @Query("DELETE FROM scraped_items WHERE siteId = :siteId") suspend fun deleteForSite(siteId: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadEntity?
    @Insert suspend fun insert(d: DownloadEntity): Long
    @Update suspend fun update(d: DownloadEntity)
    @Delete suspend fun delete(d: DownloadEntity)
}
