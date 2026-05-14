package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.network.ApiService
import com.example.easy_billing.network.HsnVerificationResponse
import com.example.easy_billing.network.ProductNameVerifyResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.network.VariantListResponse

/**
 * Wraps the three "global verification" endpoints exposed by the
 * shop backend:
 *
 *   • [verifyHsn]          — is this a real HSN?
 *   • [variantsFor]        — what variants does this product have?
 *   • [verifyProductName]  — is the product name globally known?
 *
 * Calls are intentionally `Result`-typed so callers can render
 * "valid / invalid / no internet" without throwing.
 */
class ProductVerificationRepository private constructor(
    val api: ApiService,
    val tokenProvider: () -> String?
) {

    suspend fun verifyHsn(hsn: String): Result<HsnVerificationResponse> = runCatching {
        val token = tokenProvider() ?: error("Not signed in")
        api.verifyHsn("Bearer $token", hsn.trim())
    }

    suspend fun variantsFor(productName: String): Result<VariantListResponse> = runCatching {
        val token = tokenProvider() ?: error("Not signed in")
        api.getProductVariants("Bearer $token", productName.trim())
    }

    suspend fun verifyProductName(name: String): Result<ProductNameVerifyResponse> = runCatching {
        val token = tokenProvider() ?: error("Not signed in")
        api.verifyProductName("Bearer $token", name.trim())
    }

    companion object {
        @Volatile private var INSTANCE: ProductVerificationRepository? = null

        fun get(context: Context): ProductVerificationRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProductVerificationRepository(
                    api = RetrofitClient.api,
                    tokenProvider = {
                        context.applicationContext
                            .getSharedPreferences("auth", Context.MODE_PRIVATE)
                            .getString("TOKEN", null)
                    }
                ).also { INSTANCE = it }
            }
        }
    }
}
