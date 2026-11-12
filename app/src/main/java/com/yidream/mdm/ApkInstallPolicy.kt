package com.yidream.mdm

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val signingHistory: Set<String> = emptySet()
)

/** Android still makes the final decision; these checks explain refusals before submission. */
internal object ApkInstallPolicy {
    enum class Rejection { WRONG_PACKAGE, WRONG_VERSION, MISSING_SIGNATURE, SIGNATURE_MISMATCH, DOWNGRADE, NOT_INSTALLED, SAME_VERSION }

    fun rejection(expectedPackage: String, expectedVersion: Long, candidate: ApkIdentity, installed: ApkIdentity?): Rejection? {
        if (candidate.packageName != expectedPackage) return Rejection.WRONG_PACKAGE
        if (expectedVersion <= 0 || candidate.versionCode != expectedVersion) return Rejection.WRONG_VERSION
        if (candidate.signers.isEmpty()) return Rejection.MISSING_SIGNATURE
        if (installed == null) return Rejection.NOT_INSTALLED
        if (installed.packageName != expectedPackage) return Rejection.WRONG_PACKAGE
        if (installed.signers.isEmpty()) return Rejection.MISSING_SIGNATURE
        val sameSigners = installed.signers == candidate.signers
        // PackageManager supplies the verified certificate history for a valid key rotation.
        val rotation = installed.signers.size == 1 && candidate.signers.size == 1 &&
            installed.signers.single() in candidate.signingHistory
        if (!sameSigners && !rotation) return Rejection.SIGNATURE_MISMATCH
        if (candidate.versionCode < installed.versionCode) return Rejection.DOWNGRADE
        if (candidate.versionCode == installed.versionCode) return Rejection.SAME_VERSION
        return null
    }

    fun installationConfirmed(expectedPackage: String, expectedVersion: Long, installed: ApkIdentity?): Boolean =
        expectedVersion > 0 && installed != null && installed.packageName == expectedPackage && installed.versionCode >= expectedVersion
}
