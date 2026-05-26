package uk.co.twinscrollgridbalancer.tsgbheater.ui.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.twinscrollgridbalancer.tsgbheater.data.group.HeaterGroup
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FlameOrange
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbNavy

@Composable
fun GroupsScreen(
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
) {
    val vm: GroupsViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Groups",
            subtitle = "Control multiple heaters as one",
            onBack   = onBack,
            actions = {
                IconButton(onClick = onCreateGroup) {
                    Icon(Icons.Filled.Add, contentDescription = "New group", tint = Color.White)
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.groups.isEmpty()) item { EmptyState(canCreate = ui.bound.size >= 2, onCreate = onCreateGroup) }
            items(ui.groups, key = { it.id }) { g ->
                GroupRow(g, memberLabel(g, ui.bound.associateBy { it.mac })) { onOpenGroup(g.id) }
            }
        }
    }
}

private fun memberLabel(
    group: HeaterGroup,
    byMac: Map<String, uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDevice>,
): String {
    if (group.memberMacs.isEmpty()) return "No members"
    val names = group.memberMacs.map { byMac[it]?.name ?: "—" }
    return when (names.size) {
        1    -> "1 heater · ${names.first()}"
        2    -> "2 heaters · ${names.joinToString(" · ")}"
        else -> "${names.size} heaters · ${names.take(2).joinToString(" · ")} + ${names.size - 2}"
    }
}

@Composable
private fun GroupRow(group: HeaterGroup, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Groups,
            contentDescription = null,
            tint = FlameOrange,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun EmptyState(canCreate: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("No groups yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(
            text  = if (canCreate)
                "Create a group to control two or more heaters together — Heat all, " +
                "Stop all, set the same target temperature, etc."
            else
                "Pair at least two heaters first, then come back here to bundle them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canCreate) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TsgbNavy)
                    .clickable(onClick = onCreate)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
                Text("New group",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White)
            }
        }
    }
}
