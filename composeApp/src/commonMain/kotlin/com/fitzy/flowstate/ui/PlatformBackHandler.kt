package com.fitzy.flowstate.ui

import androidx.compose.runtime.Composable

/** Handles the system Back action while composed. Android: back button or gesture. Desktop: no-op (Esc is handled in the screen). */
@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)
