package com.fitzy.flowstate

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FlowState",
        state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(900, 600)
        App()
    }
}
