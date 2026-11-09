package com.fitzy.flowstate

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.fitzy.flowstate.app.FileTransfer

/** Uses the system file picker, so no storage permission is needed. Must be created before the activity starts. */
class AndroidFileTransfer(private val activity: ComponentActivity) : FileTransfer {
    override val available = true
    private var pendingText: String? = null
    private var onSaved: ((Boolean) -> Unit)? = null
    private var onOpened: ((String?) -> Unit)? = null

    private val create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val ok = uri != null && runCatching {
            activity.contentResolver.openOutputStream(uri)?.use { it.write(pendingText.orEmpty().toByteArray()) } != null
        }.getOrDefault(false)
        pendingText = null
        onSaved?.invoke(ok); onSaved = null
    }

    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val text = uri?.let { runCatching { activity.contentResolver.openInputStream(it)?.use { s -> s.readBytes().decodeToString() } }.getOrNull() }
        onOpened?.invoke(text); onOpened = null
    }

    override fun save(suggestedName: String, text: String, onDone: (Boolean) -> Unit) {
        pendingText = text; onSaved = onDone
        create.launch(suggestedName)
    }

    override fun open(onText: (String?) -> Unit) {
        onOpened = onText
        open.launch(arrayOf("application/json", "text/plain", "*/*"))
    }
}
