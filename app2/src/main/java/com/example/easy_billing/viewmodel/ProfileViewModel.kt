package com.example.easy_billing.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.AppDatabase
import com.example.easy_billing.db.GstProfile
import com.example.easy_billing.db.StoreInfo
import com.example.easy_billing.repository.GstProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * View-state holder for the read-only Profile screen.
 *
 * After the GST refactor:
 *   • No verify, no edit, no tax-rate editing here.
 *   • This VM only exposes the current cached store/GST rows and a
 *     light pull-to-refresh that delegates to the repository.
 */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GstProfileRepository.get(app)
    private val storeInfoDao = AppDatabase.getDatabase(app).storeInfoDao()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val storeInfo: StateFlow<StoreInfo?> = storeInfoDao.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val gstProfile: StateFlow<GstProfile?> = repo.observe().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    fun refreshFromBackend() {
        val token = readToken() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            repo.refreshFromServer(token)
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    private fun readToken(): String? =
        getApplication<Application>()
            .getSharedPreferences("auth", Context.MODE_PRIVATE)
            .getString("TOKEN", null)

    data class UiState(val loading: Boolean = false)
}
