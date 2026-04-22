package com.example.easy_billing.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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

                            val syncManager = SyncManager(context)
                            syncManager.syncAll()
                            syncManager.pullInventory()

                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isSyncing = false
                        }
                    }
                }
            }
        )
    }
}