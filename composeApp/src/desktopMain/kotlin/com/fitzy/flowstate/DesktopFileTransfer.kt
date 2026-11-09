package com.fitzy.flowstate

import com.fitzy.flowstate.app.FileTransfer
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** AWT file dialogs. Calls come from the UI thread; the dialogs are modal. */
class DesktopFileTransfer : FileTransfer {
    override val available = true

    override fun save(suggestedName: String, text: String, onDone: (Boolean) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Save backup", FileDialog.SAVE).apply { file = suggestedName; isVisible = true }
        val name = dialog.file
        onDone(name != null && runCatching { File(dialog.directory, name).writeText(text) }.isSuccess)
    }

    override fun open(onText: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Restore backup", FileDialog.LOAD).apply { isVisible = true }
        val name = dialog.file
        onText(if (name == null) null else runCatching { File(dialog.directory, name).readText() }.getOrNull())
    }
}
