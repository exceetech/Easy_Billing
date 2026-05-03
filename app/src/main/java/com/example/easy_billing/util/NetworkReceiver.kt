package com.example.easy_billing.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.widget.Toast
import com.example.easy_billing.MainActivity
import com.example.easy_billing.network.RetrofitClient
import com.example.easy_billing.sync.SyncManager
import kotlinx.coroutines.*

class NetworkReceiver(private val context: Context) {

    private var isSyncing = false

    fun startListening() {

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {

                    if (isSyncing) return
                    isSyncing = true

                    CoroutineScope(Dispatchers.IO).launch {

                        try {

                            delay(1500)

                            val isBackendAlive = checkBackend()

                            if (!isBackendAlive) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Server not reachable", Toast.LENGTH_SHORT).show()
                                }
                                forceLogout("Server not reachable")
                                return@launch
                            }

                            // ✅ BACKEND OK → SYNC
                            val syncManager = SyncManager(context)
                            syncManager.syncAll()
                            syncManager.pullInventory()

                        } catch (e: Exception) {
                            e.printStackTrace()
                            forceLogout("Server error")
                        } finally {
                            isSyncing = false
                        }
                    }
                }
            }
        )
    }

    // ================= BACKEND CHECK =================

    private suspend fun checkBackend(): Boolean {

        return try {

            val token = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
                .getString("TOKEN", null) ?: return false

            withTimeout(3000) {
                RetrofitClient.api.getSubscription("Bearer $token")
            }

            true

        } catch (e: Exception) {
            false
        }
    }

    // ================= FORCE LOGOUT =================

    private fun forceLogout(reason: String) {

        val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        CoroutineScope(Dispatchers.Main).launch {

            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()

            val intent = Intent(context, MainActivity::class.java)
            intent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

            context.startActivity(intent)
        }
    }
}
