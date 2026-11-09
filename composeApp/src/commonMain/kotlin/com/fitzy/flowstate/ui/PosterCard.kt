package com.fitzy.flowstate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitzy.flowstate.domain.Candidate

/** Placeholder poster. Real artwork (TMDB, then Metahub) will load here through Coil. */
@Composable
fun PosterCard(c: Candidate, modifier: Modifier = Modifier) {
    val hue = (c.id.tmdbId % 360).toInt()
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.hsv(hue.toFloat(), 0.35f, 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            c.name,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            textAlign = TextAlign.Center,
            color = Color(0xFFE8EBF2),
        )
    }
}
