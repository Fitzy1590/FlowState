package com.fitzy.flowstate

import com.fitzy.flowstate.secrets.InMemorySecretStore
import com.fitzy.flowstate.secrets.KeyValueStorage
import com.fitzy.flowstate.secrets.Protector
import com.fitzy.flowstate.secrets.ProtectedSecretStore
import com.fitzy.flowstate.secrets.SecretStore
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Properties

/** Windows Data Protection API: values can only be decrypted by the same Windows user. */
class DpapiProtector : Protector {
    override fun protect(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)
    override fun unprotect(data: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(data)
}

/** Properties file holding already-encrypted values. */
class PropertiesStorage(private val file: File) : KeyValueStorage {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

    @Synchronized override fun get(key: String): String? = props.getProperty(key)
    @Synchronized override fun put(key: String, value: String) { props.setProperty(key, value); save() }
    @Synchronized override fun remove(key: String) { props.remove(key); save() }
    private fun save() = file.outputStream().use { props.store(it, null) }
}

/** DPAPI on Windows. Elsewhere (developer machines) keys are kept in memory only and are not saved. */
fun desktopSecretStore(dataDir: File): SecretStore =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        ProtectedSecretStore(PropertiesStorage(File(dataDir, "secrets.properties")), DpapiProtector())
    else InMemorySecretStore()
