package app.seniorremote.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Buttons the senior-facing remote can show. Protocol key codes live in the protocol adapter. */
enum class RemoteKey(val repeatable: Boolean, val capability: Capability) {
    UP(true, Capability.NAVIGATION),
    DOWN(true, Capability.NAVIGATION),
    LEFT(true, Capability.NAVIGATION),
    RIGHT(true, Capability.NAVIGATION),
    OK(false, Capability.OK),
    BACK(false, Capability.BACK),
    HOME(false, Capability.HOME),
    VOLUME_UP(true, Capability.VOLUME),
    VOLUME_DOWN(true, Capability.VOLUME),
    MUTE(false, Capability.MUTE),
    POWER(false, Capability.POWER_STANDBY),
}

/** Things the administrator verifies on the real TV during setup. */
@Serializable
enum class Capability {
    NAVIGATION,
    OK,
    BACK,
    HOME,
    VOLUME,
    MUTE,
    POWER_STANDBY,
    POWER_WAKE,
    TEXT_ENTRY,
}

@Serializable
enum class CapabilityStatus {
    /** The administrator confirmed the TV visibly reacted. */
    SUPPORTED_TESTED,

    /** The administrator confirmed the TV did not react. Hidden from the senior. */
    UNSUPPORTED,

    /** Not tested yet. Transmitting a command is not proof of support. */
    NOT_VERIFIED,
}

@Serializable
enum class ProtocolType { ANDROID_TV_REMOTE_V2 }

/**
 * One saved TV. Holds identity and configuration only; secrets live in the [CredentialStore]
 * under [credentialRef]. Everything internal refers to a TV by [id], never by its name.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SavedTv(
    /** Stable internal identity. Survives renaming and re-pairing. */
    val id: String = "",
    /** Name the TV gave itself during setup, if any. Kept for the administrator. */
    @JsonNames("displayName")
    val originalName: String? = null,
    /** Optional name chosen by a family member. */
    val customName: String? = null,
    /** Optional short description that tells this TV apart from a similarly named one. */
    val subtitle: String? = null,
    /** Optional picture chosen by the administrator (file name in the image store). */
    val imageFile: String? = null,
    /** Stable identifier advertised by discovery (if any). A hint for rediscovery, not proof. */
    val deviceId: String? = null,
    /** mDNS service instance name, used to find the TV again after an address change. */
    val serviceName: String? = null,
    /** Hex SHA-256 of the TV's public key from pairing. Public; used to spot the same TV twice. */
    val identityKey: String? = null,
    val lastKnownHost: String,
    val port: Int,
    val protocol: ProtocolType = ProtocolType.ANDROID_TV_REMOTE_V2,
    val credentialRef: String,
    val capabilities: Map<Capability, CapabilityStatus> = Capability.entries.associateWith { CapabilityStatus.NOT_VERIFIED },
    val lastConnectedAtEpochMs: Long? = null,
) {
    /** The one name shown everywhere: remote, chooser and settings. */
    val displayName: String get() = customName ?: originalName ?: "TV"

    fun status(capability: Capability): CapabilityStatus =
        capabilities[capability] ?: CapabilityStatus.NOT_VERIFIED

    /**
     * Whether a key should appear on the senior-facing remote. Unsupported controls are hidden.
     * Power is shown only once standby was actually verified, because an unreliable power
     * button is worse than none.
     */
    fun showsKey(key: RemoteKey): Boolean = when (key) {
        RemoteKey.POWER -> status(Capability.POWER_STANDBY) == CapabilityStatus.SUPPORTED_TESTED
        else -> status(key.capability) != CapabilityStatus.UNSUPPORTED
    }
}

/** A TV found during setup or rediscovery. */
data class TvCandidate(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val deviceId: String?,
    /** Secondary details (shown to the administrator only) to tell similar TVs apart. */
    val details: String,
)

/**
 * Pairing material for one TV. [serverPublicKeySha256] pins the TV's identity: a device that
 * presents a different key is never controlled, even if it has the same name or address.
 */
class TvCredentials(
    val clientPrivateKeyPkcs8: ByteArray,
    val clientCertificateDer: ByteArray,
    val serverPublicKeySha256: ByteArray,
) {
    override fun toString(): String = "TvCredentials(<redacted>)"
}
