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
    val profileStatus: String = "Yeni"
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
    val scrapedAt: Long = System.currentTimeMillis()
)
