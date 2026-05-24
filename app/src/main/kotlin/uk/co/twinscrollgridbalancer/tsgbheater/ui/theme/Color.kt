package uk.co.twinscrollgridbalancer.tsgbheater.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colours from the website (Styles/tailwind.src.css + wwwroot/css/site.css):
//   --color-tsgb-navy:      #012169
//   --color-tsgb-red:       #C8102E
//   --color-tsgb-burgundy:  #6b0c1c
// Body background:          #F1F3F9 with corner radial gradients of those three.
val TsgbNavy     = Color(0xFF012169)
val TsgbNavyDk   = Color(0xFF0A1F6B)
val TsgbRed      = Color(0xFFC8102E)
val TsgbRedDk    = Color(0xFF9B0D23)
val TsgbBurgundy = Color(0xFF6B0C1C)

// Light surfaces — match the website's body + glass-panel cards.
val Body0        = Color(0xFFF1F3F9)   // page background
val Body1        = Color(0xFFFFFFFF)   // primary card / surface
val Body2        = Color(0xFFF6F7FB)   // recessed surface (subtle off-white)
val LineGrey     = Color(0xFFE2E5EE)   // hairline borders
val LineGreyDk   = Color(0xFFCBD2DF)   // outline / divider

// Text on light surfaces.
val InkHi        = Color(0xFF0F172A)   // body
val InkMd        = Color(0xFF374151)   // secondary
val InkLo        = Color(0xFF6B7280)   // captions, labels

// Status colours.
val OkGreen    = Color(0xFF16A34A)
val WarnAmber  = Color(0xFFD97706)
val ErrRed     = TsgbRed

// Heater-specific accents — used for icon tinting on the Me list and on the
// device tab to mark domains (combustion / fan / fuel / temperature).
val FlameOrange = Color(0xFFEA580C)   // combustion
val CoolBlue    = Color(0xFF1D4ED8)   // fan / airflow
val FuelAmber   = Color(0xFFB45309)   // fuel
val ProbeSlate  = Color(0xFF334155)   // sensor / probe
