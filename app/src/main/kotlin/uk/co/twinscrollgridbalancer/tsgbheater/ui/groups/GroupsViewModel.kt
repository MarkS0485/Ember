package uk.co.twinscrollgridbalancer.tsgbheater.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.data.group.HeaterGroup
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDevice
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator

data class GroupListUiState(
    val groups: List<HeaterGroup> = emptyList(),
    val bound:  List<BoundDevice> = emptyList(),
)

class GroupsViewModel : ViewModel() {

    private val store = ServiceLocator.groups
    private val devices = ServiceLocator.boundDevices

    val ui: StateFlow<GroupListUiState> = combine(
        store.all, devices.all,
    ) { g, d -> GroupListUiState(g, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupListUiState())

    fun createGroup(name: String, members: List<String>) = viewModelScope.launch {
        store.create(name, members)
    }

    fun deleteGroup(id: String) = viewModelScope.launch { store.delete(id) }
}
