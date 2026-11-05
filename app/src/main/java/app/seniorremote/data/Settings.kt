package app.seniorremote.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteSettings(
    val haptics: Boolean = true,
    val buttonSounds: Boolean = false,
    val keepScreenOn: Boolean = false,
)

/** Administrator preferences for the senior-facing remote. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<RemoteSettings> = _settings.asStateFlow()

    fun update(transform: (RemoteSettings) -> RemoteSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean("haptics", next.haptics)
            .putBoolean("sounds", next.buttonSounds)
            .putBoolean("keep_screen_on", next.keepScreenOn)
            .apply()
        _settings.value = next
    }

    private fun read() = RemoteSettings(
        haptics = prefs.getBoolean("haptics", true),
        buttonSounds = prefs.getBoolean("sounds", false),
        keepScreenOn = prefs.getBoolean("keep_screen_on", false),
    )
}

/**
 * A simple PIN that keeps configuration away from accidental taps. This is protection against
 * accidental changes, not strong security. Routine remote use never asks for it.
 */
class AdminPinStore(context: Context) {
    private val prefs = context.getSharedPreferences("admin", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        require(isValidFormat(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("pin_salt", salt.toHex())
            .putString("pin_hash", hash(pin, salt).toHex())
            .apply()
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null)?.hexToBytes() ?: return false
        val expected = prefs.getString("pin_hash", null)?.hexToBytes() ?: return false
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    companion object {
        fun isValidFormat(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

        private fun hash(pin: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun String.hexToBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
