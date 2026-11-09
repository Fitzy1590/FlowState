package com.fitzy.flowstate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import com.fitzy.flowstate.app.NoFileTransfer
import com.fitzy.flowstate.voice.NoVoiceInput
import okio.Path.Companion.toOkioPath
import java.io.File

private const val POSTER_CACHE_BYTES = 100L * 1024 * 1024

class MainActivity : ComponentActivity() {
    private var notificationReply: ((Boolean) -> Unit)? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationReply?.invoke(granted)
        notificationReply = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FlowStateApp).container

        // Things that need a visible activity are handed to the shared layer while this activity exists.
        container.hooks.voice = AndroidVoiceInput(this)
        container.hooks.files = AndroidFileTransfer(this)
        container.hooks.requestNotificationPermission = { reply ->
            val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsAsk) { notificationReply = reply; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } else reply(true)
        }

        setContent {
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .diskCache { DiskCache.Builder().directory(File(cacheDir, "poster-cache").toOkioPath()).maxSizeBytes(POSTER_CACHE_BYTES).build() }
                    .build()
            }
            App(container)
        }
    }

    override fun onDestroy() {
        val hooks = (application as FlowStateApp).container.hooks
        hooks.voice = NoVoiceInput
        hooks.files = NoFileTransfer
        hooks.requestNotificationPermission = { it(true) }
        super.onDestroy()
    }
}
