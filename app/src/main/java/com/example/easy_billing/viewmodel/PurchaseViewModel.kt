package com.example.easy_billing.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.easy_billing.db.Purchase
import com.example.easy_billing.repository.ProductRepository
import com.example.easy_billing.repository.PurchaseRepository
import com.example.easy_billing.repository.PurchaseRepository.PurchaseItemDraft
import com.example.easy_billing.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [com.example.easy_billing.PurchaseActivity].
 *
 * Holds the editable invoice header + an in-memory list of
 * [PurchaseItemDraft]s. `save` persists everything in one Room
 * transaction *and* attempts an immediate backend push so the user
 * sees a precise outcome (synced / offline-queued / push failed).
 */
class PurchaseViewModel(app: Application) : AndroidViewModel(app) {

    private val purchaseRepo = PurchaseRepository.get(app)
    private val productRepo  = ProductRepository.get(app)

    private val _lines = MutableStateFlow<List<PurchaseItemDraft>>(emptyList())
    val lines: StateFlow<List<PurchaseItemDraft>> = _lines.asStateFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun addLine(line: PurchaseItemDraft) {
        _lines.value = _lines.value + line
    }

    fun removeLine(index: Int) {
        _lines.value = _lines.value.toMutableList().also {
            if (index in it.indices) it.removeAt(index)
        }
    }

    fun replaceLine(index: Int, line: PurchaseItemDraft) {
        _lines.value = _lines.value.toMutableList().also {
            if (index in it.indices) it[index] = line
        }
    }

    /** Pre-fill HSN + sales tax for a line from history. */
    suspend fun autofillForLine(name: String?, hsn: String?) =
        productRepo.autoFillFromHistory(name, hsn)

    fun save(header: Purchase) {
        viewModelScope.launch {
            val current = _lines.value
            if (current.isEmpty()) {
                _state.value = _state.value.copy(error = "Add at least one product")
                return@launch
            }
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { purchaseRepo.savePurchase(header, current) }
                .onSuccess { result ->
                    val syncMessage = when (val outcome = result.syncOutcome) {
                        is SyncManager.SyncResult.Pushed       -> "Saved and synced"
                        is SyncManager.SyncResult.NothingToDo  -> "Saved (already up-to-date)"
                        is SyncManager.SyncResult.Skipped      ->
                            "Saved offline — will sync when ${outcome.reason}"
                        is SyncManager.SyncResult.Failed       ->
                            "Saved locally. Backend push failed: ${outcome.reason}. Will retry."
                    }
                    _state.value = UiState(
                        loading = false,
                        savedPurchaseId = result.purchaseId,
                        message = syncMessage
                    )
                    _lines.value = emptyList()
                    // Also kick the broader pending queue.
                    com.example.easy_billing.sync.SyncCoordinator
                        .get(getApplication()).requestSync()
                }
                .onFailure { err ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = err.message ?: "Save failed"
                    )
                }
        }
    }

    fun clearTransient() {
        _state.value = _state.value.copy(error = null, savedPurchaseId = null, message = null)
    }

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val savedPurchaseId: Int? = null
    )
}
