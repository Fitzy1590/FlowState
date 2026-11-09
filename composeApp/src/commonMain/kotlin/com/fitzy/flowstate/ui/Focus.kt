package com.fitzy.flowstate.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.theme.FlowColors

const val FOCUS_SCALE = 1.05f // at least 1.03x
val FOCUS_BORDER = 3.dp // at least 2dp

/**
 * Visible focus for D-pad and keyboard use: the item grows 5% and gets a 3dp accent border.
 * Put it BEFORE `clickable` so it can see that item's focus.
 */
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) FOCUS_SCALE else 1f)
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(FOCUS_BORDER, FlowColors.Accent, shape) else Modifier)
}
