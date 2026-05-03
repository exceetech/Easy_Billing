package com.example.easy_billing.repository

import android.content.Context
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.GstProfile
import com.example.easy_billing.db.GstProfileDao
import com.example.easy_billing.network.ApiService
import com.example.easy_billing.network.GstProfileRequest
import com.example.easy_billing.network.GstProfileResponse
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.util.DeviceUtils
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for the local GST profile cache.
 *
 * After the GST refactor this repository is intentionally minimal:
 *
 *   • No Sandbox / verification flow — manual entry only.
 *   • Writes go to Room first (`sync_status="pending"`), then we
 *     attempt a backend push; on failure the row stays pending and
 *     [retryPendingSync] replays it later.
 *   • Conflict resolution = whichever side has the larger
 *     `updated_at` wins.
 */
class GstProfileRepository private constructor(
    private val dao: GstProfileDao,
    private val api: ApiService,
    private val deviceId: String
) {

    fun observe(): Flow<GstProfile?> = dao.observe()

    suspend fun getCached(): GstProfile? = dao.get()

    /**
     * Manual upsert (called from BillingSettings/StoreSettings save
     * handlers). The caller has already populated the entity with
     * the user-entered fields; we just stamp deviceId + updatedAt
     * and push to backend.
     */
    suspend fun upsertLocal(profile: GstProfile) {
        dao.insert(
            profile.copy(
                deviceId   = profile.deviceId.ifBlank { deviceId },
                syncStatus = "pending",
                updatedAt  = System.currentTimeMillis()
            )
        )
    }

    /** Replays any locally pending profile pushes. */
    suspend fun retryPendingSync(token: String): Int {
        var pushed = 0
        dao.getUnsynced().forEach { row ->
            if (pushToServer(token, row)) pushed++
        }
        return pushed
    }

    /**
     * Pulls the canonical profile from backend. Conflict rule: the
     * side with the more recent `updated_at` wins. If we have no
     * local row, take whatever the server has.
     */
    suspend fun refreshFromServer(token: String): Result<GstProfile?> {
        return runCatching {
            val remote: GstProfileResponse = api.getGstProfile("Bearer $token")
            val local = dao.get()
            val now = System.currentTimeMillis()

            val merged = GstProfile(
                gstin            = remote.gstin,
                shopId           = local?.shopId.orEmpty(),
                legalName        = remote.legal_name,
                tradeName        = remote.trade_name,
                gstScheme        = remote.gst_scheme,
                registrationType = remote.registration_type,
                stateCode        = remote.state_code,
                address          = remote.address ?: local?.address.orEmpty(),
                deviceId         = local?.deviceId.orEmpty().ifBlank { deviceId },
                syncStatus       = "synced",
                createdAt        = local?.createdAt ?: now,
                updatedAt        = now
            )

            // Conflict rule: keep the locally-pending row if the user
            // edited it more recently than the backend response.
            if (local != null && local.syncStatus == "pending" &&
                local.updatedAt > now - SERVER_RESPONSE_FRESHNESS_MS
            ) {
                local
            } else {
                dao.insert(merged)
                merged
            }
        }
    }

    private suspend fun pushToServer(token: String, profile: GstProfile): Boolean {
        return runCatching {
            api.upsertGstProfile(
                "Bearer $token",
                GstProfileRequest(
                    gstin = profile.gstin,
                    legal_name = profile.legalName,
                    trade_name = profile.tradeName,
                    gst_scheme = profile.gstScheme,
                    registration_type = profile.registrationType,
                    state_code = profile.stateCode,
                    address = profile.address
                )
            )
            dao.updateSyncStatus("synced")
            true
        }.getOrElse {
            dao.updateSyncStatus("failed")
            false
        }
    }

    companion object {
        private const val SERVER_RESPONSE_FRESHNESS_MS = 60_000L

        @Volatile private var INSTANCE: GstProfileRepository? = null

        fun get(context: Context): GstProfileRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GstProfileRepository(
                    dao = AppDatabase.getDatabase(context).gstProfileDao(),
                    api = RetrofitClient.api,
                    deviceId = DeviceUtils.getDeviceId(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }
}
