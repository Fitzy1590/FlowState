package com.fitzy.flowstate

import com.fitzy.flowstate.links.LinkLauncher
import java.awt.Desktop
import java.net.URI

/** Uses the OS default handler, so `stremio://` works when Stremio has registered its protocol (it does on Windows). */
class DesktopLinkLauncher : LinkLauncher {
    override fun open(uri: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
        Desktop.getDesktop().browse(URI(uri))
    }.isSuccess
}
