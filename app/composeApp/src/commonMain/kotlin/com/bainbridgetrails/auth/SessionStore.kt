package com.bainbridgetrails.auth

import com.bainbridgetrails.net.JsonFile
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A signed-in session (api.md: opaque token, valid 90 days). */
@Serializable
data class Session(
    val token: String,
    /** Epoch ms. */
    val expiresAt: Long,
    val userId: String,
    /** `google` | `apple` | `dev`. */
    val provider: String,
)

@Serializable
private data class DeviceFile(val deviceId: String)

/**
 * Persists the [Session] and a stable dev-auth device id as plain JSON files in [dir] (use
 * [privateNoBackupDir]: app-private, excluded from backup; see that function for why not Keychain/Keystore).
 * Blocking I/O: call off Main.
 */
class SessionStore(dir: String, private val now: () -> Long) {
    private val session = JsonFile("$dir/session.json", Session.serializer())
    private val device = JsonFile("$dir/device.json", DeviceFile.serializer())

    /** The stored session, or null when missing, unreadable or expired. */
    fun load(): Session? = session.read()?.takeIf { it.expiresAt > now() }

    fun save(s: Session) = session.write(s)

    fun clear() = session.delete()

    /** Random per install, never reset by sign-out (dev auth: same device id → same account). */
    @OptIn(ExperimentalUuidApi::class)
    fun deviceId(): String = device.read()?.deviceId ?: "dev-${Uuid.random()}".also { device.write(DeviceFile(it)) }
}
