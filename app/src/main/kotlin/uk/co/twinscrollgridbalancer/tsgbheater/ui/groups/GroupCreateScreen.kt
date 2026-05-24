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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar

@Composable
fun GroupCreateScreen(onBack: () -> Unit, onCreated: () -> Unit) {
    val vm: GroupsViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateMapOf<String, Boolean>() }

    val canCreate = name.isNotBlank() && picked.values.count { it } >= 1

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "New group",
            subtitle = "Pick members",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(ui.bound, key = { it.mac }) { dev ->
                val checked = picked[dev.mac] == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                        .clickable { picked[dev.mac] = !checked }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = { picked[dev.mac] = it })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dev.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(dev.mac,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val members = picked.filterValues { it }.keys.toList()
                        vm.createGroup(name, members)
                        onCreated()
                    },
                    enabled = canCreate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create")
                }
            }
            if (ui.bound.isEmpty()) {
                item {
                    Text(
                        text  = "You haven't paired any heaters yet. Pair one or more on " +
                                "the Scan tab, then come back here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}
