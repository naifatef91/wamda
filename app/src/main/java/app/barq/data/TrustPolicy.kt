package app.barq.data

import java.security.MessageDigest

/**
 * The one rule behind "trusted device": a name alone is never enough, the certificate fingerprint recorded
 * when the user trusted it must match what the peer presents now. A device that merely reuses the name
 * (or a reinstalled app with a new certificate) is asked about again.
 */
object TrustPolicy {
    fun sameFingerprint(saved: String?, presented: String?): Boolean {
        if (saved == null || presented == null) return false
        return MessageDigest.isEqual(saved.toByteArray(), presented.toByteArray())
    }

    /** [trusted] maps device name to the fingerprint saved for it. */
    fun autoAccept(enabled: Boolean, trusted: Map<String, String>, name: String, presented: String?): Boolean =
        enabled && sameFingerprint(trusted[name], presented)
}
