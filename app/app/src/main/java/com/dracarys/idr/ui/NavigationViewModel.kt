package com.dracarys.idr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dracarys.idr.ui.state.FakeNavigationRepository
import com.dracarys.idr.ui.state.NavigationRepository
import com.dracarys.idr.ui.state.NavigationState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for [com.dracarys.idr.ui.screen.NavigationScreen].
 *
 * Accepts a [NavigationRepository] via constructor injection. The default production factory
 * supplies [FakeNavigationRepository]; swap in `LiveNavigationRepository` when the real pipeline
 * is ready — no changes to the ViewModel or any composable are required.
 *
 * ### Hilt migration path (when ready)
 * 1. Add `@HiltViewModel` annotation to this class.
 * 2. Change `private val repo` to `@Inject constructor(private val repo: NavigationRepository)`.
 * 3. Delete [Factory] and update the call-site in [com.dracarys.idr.MainActivity].
 */
class NavigationViewModel(
    private val repo: NavigationRepository,
) : ViewModel() {

    /**
     * Hot [StateFlow] of [NavigationState] collected with lifecycle awareness.
     * [SharingStarted.WhileSubscribed] ensures no emissions during background/stopped state.
     */
    val state: StateFlow<NavigationState> = repo.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = NavigationState.INITIAL,
        )

    val isDebugOutageActive: StateFlow<Boolean> = repo.isDebugOutageActive
    val outageHistory: StateFlow<List<com.dracarys.idr.logging.OutageValidationRecord>> = repo.outageHistory
    val diagnosticState: StateFlow<com.dracarys.idr.ui.state.DiagnosticState> = repo.diagnosticState

    fun toggleDebugOutage() {
        repo.toggleDebugOutage()
    }

    fun refreshLocation() {
        repo.refreshLocation()
    }

    // ── Factory ────────────────────────────────────────────────────────────────

    /**
     * [ViewModelProvider.Factory] using constructor injection.
     * Default [repo] is [FakeNavigationRepository] so the app runs immediately without wiring.
     */
    class Factory(
        private val repo: NavigationRepository = FakeNavigationRepository(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NavigationViewModel::class.java))
            return NavigationViewModel(repo) as T
        }
    }
}
