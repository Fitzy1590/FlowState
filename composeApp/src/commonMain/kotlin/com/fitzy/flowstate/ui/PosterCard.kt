package com.fitzy.flowstate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fitzy.flowstate.data.tmdb.PosterUrls
import com.fitzy.flowstate.domain.Candidate

/**
 * Poster with a colored placeholder behind it. Tries TMDB first, then Metahub; if every source fails
 * (or the title has no poster data) the placeholder with the title stays.
 */
@Composable
fun PosterCard(c: Candidate, modifier: Modifier = Modifier) {
    val hue = (c.id.tmdbId % 360).toInt()
    val urls = remember(c.posterPath, c.imdbId) { PosterUrls.candidates(c.posterPath, c.imdbId) }
    var attempt by remember(urls) { mutableIntStateOf(0) }
    var loaded by remember(urls) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.hsv(hue.toFloat(), 0.35f, 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                c.name,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                textAlign = TextAlign.Center,
                color = Color(0xFFE8EBF2),
            )
        }
        urls.getOrNull(attempt)?.let { url ->
            key(url) {
                AsyncImage(
                    model = url,
                    contentDescription = c.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { loaded = true },
                    onError = { attempt++ },
                )
            }
        }
    }
}
