package uk.co.twinscrollgridbalancer.tsgbheater.ui.onboarding

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppMode
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppModeStore
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.CoolBlue
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbNavy

// First-launch landing. Two big choices: drive heaters directly over
// Bluetooth, or connect to a Windows laptop that's running the API
// server. Whatever the user picks is persisted; subsequent launches
// skip this screen and boot straight into the chosen mode.
//
// Same screen is reused when the user taps "Switch Mode" later. The
// `current` parameter highlights the active choice so re-entry doesn't
// feel like a blank slate.
@Composable
fun ModePickerScreen(
    current: AppMode = AppMode.UNSET,
    onPicked: (AppMode) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val vm: ModePickerViewModel = viewModel(
        factory = ModePickerViewModel.Factory(LocalContext.current.applicationContext as Application)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        BrandTopBar(
            title    = "Choose mode",
            subtitle = if (current == AppMode.UNSET)
                "How would you like to talk to your heaters?"
            else
                "Switch how this phone connects to your heaters",
            onBack   = onBack,
        )

        LazyColumn(
            modifier            = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ModeCard(
                    icon      = Icons.Filled.Bluetooth,
                    accent    = TsgbNavy,
                    title     = "Local mode",
                    headline  = "Bluetooth",
                    body      = "Phone talks directly to the heater over BLE. " +
                                "Use this when you're near the van/cabin and don't need internet.",
                    isCurrent = current == AppMode.LOCAL,
                    onClick   = {
                        vm.persist(AppMode.LOCAL)
                        onPicked(AppMode.LOCAL)
                    },
                )
            }
            item {
                ModeCard(
                    icon      = Icons.Filled.Cloud,
                    accent    = CoolBlue,
                    title     = "Remote mode",
                    headline  = "Internet (laptop API)",
                    body      = "Phone talks to a Windows PC running the TSGB Heater app, " +
                                "and that PC talks to the heaters. Use this from anywhere " +
                                "as long as the laptop is online.",
                    isCurrent = current == AppMode.REMOTE,
                    onClick   = {
                        vm.persist(AppMode.REMOTE)
                        onPicked(AppMode.REMOTE)
                    },
                )
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text     = "You can switch later from " +
                               (if (current == AppMode.REMOTE) "the Remote screen's top bar."
                                else "the Account tab → Switch mode."),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    headline: String,
    body: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isCurrent) accent else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isCurrent) 2.dp else 1.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = accent,
                modifier           = Modifier.size(32.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isCurrent) CurrentChip(accent)
            }
            Text(
                text  = headline,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            Text(
                text     = body,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CurrentChip(accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text  = "current",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
    }
}

// ---------------------------------------------------------------------

class ModePickerViewModel(app: Application) : androidx.lifecycle.AndroidViewModel(app) {

    private val store = AppModeStore(app)

    fun persist(m: AppMode) {
        viewModelScope.launch { store.setMode(m) }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ModePickerViewModel(app) as T
    }
}
