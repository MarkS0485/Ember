package uk.co.twinscrollgridbalancer.tsgbheater.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.ErrRed
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.OkGreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.WarnAmber

@Composable
fun StatusPill(label: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val accent = when (kind) {
        StatusKind.Online   -> OkGreen
        StatusKind.Stale    -> WarnAmber
        StatusKind.Offline  -> ErrRed
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Dot(color = accent)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun Dot(color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
        drawCircle(color = color)
    }
}

enum class StatusKind { Online, Stale, Offline }
