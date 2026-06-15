package com.emberheat.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.emberheat.data.group.GroupController
import com.emberheat.data.group.HeaterGroup
import com.emberheat.data.store.BoundDevice
import com.emberheat.di.ServiceLocator

data class GroupDetailUiState(
    val group:     HeaterGroup?              = null,
    val members:   List<BoundDevice>          = emptyList(),
    val orphans:   List<String>               = emptyList(), // member MACs no longer bound
    val candidates: List<BoundDevice>         = emptyList(),
    val progress:  GroupController.Progress   = GroupController.Progress.Idle,
)

class GroupDetailViewModel(private val groupId: String) : ViewModel() {

    private val groupsStore  = ServiceLocator.groups
    private val devicesStore = ServiceLocator.boundDevices
    private val ctl          = ServiceLocator.groupCtl

    val ui: StateFlow<GroupDetailUiState> = combine(
        groupsStore.all.map { all -> all.firstOrNull { it.id == groupId } },
        devicesStore.all,
        ctl.progress,
    ) { group, bound, progress ->
        if (group == null) GroupDetailUiState(progress = progress)
        else {
            val byMac    = bound.associateBy { it.mac }
            val members  = group.memberMacs.mapNotNull { byMac[it] }
            val orphans  = group.memberMacs.filterNot { byMac.containsKey(it) }
            val candidates = bound.filterNot { it.mac in group.memberMacs }
            GroupDetailUiState(group, members, orphans, candidates, progress)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailUiState())

    fun start()     = ui.value.group?.let { viewModelScope.launch { ctl.start(it) } }
    fun stop()      = ui.value.group?.let { viewModelScope.launch { ctl.stop(it) } }
    fun ventilate() = ui.value.group?.let { viewModelScope.launch { ctl.ventilate(it) } }

    fun setTarget(c: Int) = ui.value.group?.let { viewModelScope.launch { ctl.setTarget(it, c) } }
    fun setGear(g: Int)   = ui.value.group?.let { viewModelScope.launch { ctl.setGear(it, g) } }

    fun rename(newName: String) = viewModelScope.launch { groupsStore.rename(groupId, newName) }
    fun delete()                = viewModelScope.launch { groupsStore.delete(groupId) }
    fun addMember(mac: String)    = viewModelScope.launch { groupsStore.addMember(groupId, mac) }
    fun removeMember(mac: String) = viewModelScope.launch { groupsStore.removeMember(groupId, mac) }
}
