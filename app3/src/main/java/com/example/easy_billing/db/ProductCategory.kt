package com.example.easy_billing.db

import com.example.easy_billing.util.appNow

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Custom (shop-defined) product categories.
 *
 * The predefined category vocabulary ships in code
 * ([com.example.easy_billing.util.ProductCategories]); this table only
 * remembers categories the owner typed manually so they reappear in the
 * dropdown on future product entry. The category itself is also stored
 * as a plain string on [Product.category], so this table is purely a
 * convenience/lookup layer — products never depend on a row here.
 */
@Entity(
    tableName = "product_categories",
    indices = [
        Index(value = ["shop_id", "name"], unique = true)
    ]
)
data class ProductCategory(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val serverId: Int? = null,

    @ColumnInfo(name = "shop_id")
    val shopId: String = "",

    val name: String,

    val isCustom: Boolean = true,

    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = appNow()
)
