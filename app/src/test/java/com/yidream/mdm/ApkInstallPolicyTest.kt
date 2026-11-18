package com.yidream.mdm

import org.junit.Assert.*
import org.junit.Test

class ApkInstallPolicyTest {
    private val old = ApkIdentity("com.yidream.mdm", 12, setOf("old-key"))
    private val update = ApkIdentity("com.yidream.mdm", 15, setOf("old-key"))

    @Test fun refusesDifferentDebugKeysEvenWithTheCorrectPackageAndVersion() {
        assertEquals(ApkInstallPolicy.Rejection.SIGNATURE_MISMATCH,
            ApkInstallPolicy.rejection(old.packageName, 15, update.copy(signers=setOf("another-runner-key")), old))
    }
    @Test fun acceptsAnUpdateSignedByTheOriginalKey() {
        assertNull(ApkInstallPolicy.rejection(old.packageName, 15, update, old))
    }
    @Test fun acceptsVerifiedSingleSignerRotationButNotAnUnrelatedLineage() {
        val rotated=update.copy(signers=setOf("new-key"), signingHistory=setOf("old-key", "new-key"))
        assertNull(ApkInstallPolicy.rejection(old.packageName, 15, rotated, old))
        assertEquals(ApkInstallPolicy.Rejection.SIGNATURE_MISMATCH,
            ApkInstallPolicy.rejection(old.packageName, 15, rotated.copy(signingHistory=setOf("new-key")), old))
    }
    @Test fun multipleSignersMustAllMatch() {
        val installed=old.copy(signers=setOf("one", "two"))
        assertEquals(ApkInstallPolicy.Rejection.SIGNATURE_MISMATCH,
            ApkInstallPolicy.rejection(old.packageName, 15, update.copy(signers=setOf("one"),signingHistory=setOf("one","two")), installed))
    }
    @Test fun refusesAnOldApkServedWithANewManifest() {
        assertEquals(ApkInstallPolicy.Rejection.WRONG_VERSION,
            ApkInstallPolicy.rejection(old.packageName, 15, old, old))
    }
    @Test fun refusesOtherPackagesAndUnsignedArchives() {
        assertEquals(ApkInstallPolicy.Rejection.WRONG_PACKAGE,
            ApkInstallPolicy.rejection(old.packageName, 15, update.copy(packageName="com.other.app"), old))
        assertEquals(ApkInstallPolicy.Rejection.MISSING_SIGNATURE,
            ApkInstallPolicy.rejection(old.packageName, 15, update.copy(signers=emptySet()), null))
    }
    @Test fun refusesFirstInstallSameVersionAndDowngrade() {
        assertEquals(ApkInstallPolicy.Rejection.NOT_INSTALLED,
            ApkInstallPolicy.rejection(old.packageName, 15, update, null))
        assertEquals(ApkInstallPolicy.Rejection.SAME_VERSION,
            ApkInstallPolicy.rejection(old.packageName, 15, update, update))
        assertEquals(ApkInstallPolicy.Rejection.DOWNGRADE,
            ApkInstallPolicy.rejection(old.packageName, 12, old, update))
    }
    @Test fun successCallbackCannotConfirmAnInstallationStillOnVersion57() {
        assertFalse(ApkInstallPolicy.installationConfirmed(old.packageName, 15, old))
        assertFalse(ApkInstallPolicy.installationConfirmed(old.packageName, 15, null))
        assertTrue(ApkInstallPolicy.installationConfirmed(old.packageName, 15, update))
    }
    @Test fun explicitlyAuthorizedFirstInstallStillRequiresMatchingSignedArchive() {
        assertNull(ApkInstallPolicy.rejection(old.packageName, 15, update, null, allowFirstInstall = true))
        assertEquals(ApkInstallPolicy.Rejection.MISSING_SIGNATURE, ApkInstallPolicy.rejection(
            old.packageName, 15, update.copy(signers = emptySet()), null, allowFirstInstall = true
        ))
    }
}
