package com.emberheat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light scheme matching the website (#F1F3F9 body, navy nav, red accents,
// white cards). Kept light-only — the website itself has no dark mode and
// the heater readouts read better on white anyway.
private val EmberLight = lightColorScheme(
    primary               = EmberRed,
    onPrimary             = Color.White,
    primaryContainer      = Color(0xFFFFE4E6),
    onPrimaryContainer    = EmberBurgundy,
    secondary             = EmberNavy,
    onSecondary           = Color.White,
    secondaryContainer    = Color(0xFFDBE2F1),
    onSecondaryContainer  = EmberNavyDk,
    tertiary              = OkGreen,
    onTertiary            = Color.White,
    background            = Body0,
    onBackground          = InkHi,
    surface               = Body1,
    onSurface             = InkHi,
    surfaceVariant        = Body2,
    onSurfaceVariant      = InkMd,
    outline               = LineGreyDk,
    outlineVariant        = LineGrey,
    error                 = ErrRed,
    onError               = Color.White,
)

@Composable
fun EmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EmberLight,
        typography  = EmberTypography,
        content     = content,
    )
}
