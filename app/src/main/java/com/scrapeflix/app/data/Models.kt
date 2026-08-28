package com.scrapeflix.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "site_profiles")
data class SiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val itemSelector: String = "",
    val titleSelector: String = "",
    val imageSelector: String = "",
    val linkSelector: String = "a[href]",
    val descriptionSelector: String = "",
    val lastUpdated: Long? = null,
    val itemCount: Int = 0,
    val profileStatus: String = "Yeni",
    val hidden: Boolean = false
)

@Entity(tableName = "scraped_items")
data class ScrapedItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteId: Long,
    val title: String,
    val url: String,
    val imageUrl: String? = null,
    val description: String? = null,
    val category: String = "Diğer",
    val year: String? = null,
    val rating: String? = null,
    val sortOrder: Int = 0,
    val isFavorite: Boolean = false,
    val scrapedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val folderPath: String,
    val playbackFile: String = "video.mp4",
    val status: String = "İndiriliyor", // İndiriliyor, Tamamlandı, Hata, İptal Edildi
    val progress: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
