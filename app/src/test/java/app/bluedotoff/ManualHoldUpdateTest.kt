package app.bluedotoff

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.core.Entry
import app.bluedotoff.core.Identity
import app.bluedotoff.core.TargetState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Regression coverage for PACKAGE_REPLACED promotion of held and ordinary managed entries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ManualHoldUpdateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val packageName = "test.manualhold.update"
    private val signer = Signature(byteArrayOf(7, 4, 2, 9))
    private val signerDigest get() = BinderBackend.digest(signer.toByteArray())
    private val firstInstall = 1_700_000_000_000L

    private fun packageInfo(uid: Int = 10_551, first: Long = firstInstall, version: Long = 7, updated: Long = firstInstall + 10): PackageInfo {
        val signing = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signer)) }
        return PackageInfo().apply {
            packageName = this@ManualHoldUpdateTest.packageName
            versionCode = version.toInt()
            firstInstallTime = first
            lastUpdateTime = updated
            applicationInfo = ApplicationInfo().apply {
                this.packageName = this@ManualHoldUpdateTest.packageName
                this.uid = uid
                flags = ApplicationInfo.FLAG_INSTALLED
                sourceDir = "/data/app/$packageName/base.apk"
            }
            signingInfo = signing
            signatures = arrayOf(signer)
        }
    }

    private fun install(info: PackageInfo) {
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun entry(identity: Identity, state: TargetState = TargetState.FAILED_SAFE) = Entry(
        identity, "Fixture", 0x100, true, state, 1, "fixture", "boot", false,
        error = "manual safety hold", retryHide = false
    )

    /** Deliver the real package-scoped broadcast, then fence the receiver's serial goAsync worker. */
    private fun replaced(action:String = Intent.ACTION_PACKAGE_REPLACED) {
        val receiver = LifecycleReceiver()
        val filter = IntentFilter(action).apply { addDataScheme("package") }
        context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        try {
            context.sendBroadcast(Intent(action, Uri.parse("package:$packageName")))
            shadowOf(context.mainLooper).idle()
            val executorField = LifecycleReceiver::class.java.getDeclaredField("executor").apply { isAccessible = true }
            val executor = executorField.get(null) as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(context.mainLooper).idle()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun held(identity: Identity) = entry(identity).copy(
        changedByUs = true, retryHide = false, state = TargetState.FAILED_SAFE
    )

    private fun identity(info: PackageInfo) = Identity(
        packageName, 0, info.applicationInfo!!.uid, listOf(signerDigest),
        info.firstInstallTime, info.longVersionCode, info.lastUpdateTime
    )

    @Test fun heldSingletonIgnoresConsumedIdentityThenAcceptsSameInstallTimestampChange() {
        val original = packageInfo()
        install(original)
        val held = held(identity(original))
        val store = JournalStore(context)
        assertTrue(store.commit(held))

        // A duplicate delivery for the already-consumed version and timestamp must not lift the hold.
        replaced()
        assertEquals(held, store.entries().single())

        // Same package, signer, UID and first-install time: a later update timestamp is a genuine update,
        // even when the version number did not change.
        val later = packageInfo(updated = original.lastUpdateTime + 20)
        install(later)
        assertEquals("Fixture must expose the installed update and signer",identity(later),LocalEntryEvents.installedIdentity(context.packageManager,held))
        replaced()
        val result = store.entries().single()
        assertEquals(TargetState.NEEDS_REAPPLY_UPDATE, result.state)
        assertEquals(held.identity, result.identity) // The event selects work. Verification records the new identity.
        assertFalse("A manual safety hold must not become retry intent", result.retryHide)
        assertTrue("The update should now be surfaced as work", result.needsReapply)
    }

    @Test fun unavailablePackageInfoDoesNotReleaseAHold() {
        val held = held(identity(packageInfo()))
        val store = JournalStore(context)
        assertTrue(store.commit(held))
        assertNull(LocalEntryEvents.installedIdentity(context.packageManager,held))
        replaced()
        assertEquals(held,store.entries().single())
        replaced(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        assertEquals(TargetState.MISSING_TARGET,store.entries().single().state)
    }

    @Test fun lateMemberBroadcastDoesNotReopenAFailedSharedGroup() {
        val original=packageInfo()
        install(original)
        val id=identity(original)
        val other=id.copy(packageName="test.manualhold.companion")
        val members=listOf(other,id).sortedBy { it.packageName }
        val group=members.map { held(it).copy(groupIdentities=members,sharedUserId="test.shared") }
        val store=JournalStore(context)
        assertTrue(store.commitGroup(group))
        replaced()
        assertEquals(group,store.entries())
    }

    @Test fun replacementInstallationCannotInheritTheOldManagementRecord() {
        val original = packageInfo()
        install(original)
        val held = held(identity(original))
        val store = JournalStore(context)
        assertTrue(store.commit(held))

        // A fresh install has a new UID and first-install time, even if package name and signer match.
        val replacement = packageInfo(uid = 10_552, first = firstInstall + 50, updated = firstInstall + 60)
        install(replacement)
        replaced()

        val result = store.entries().single()
        assertEquals(held.identity, result.identity)
        assertFalse("The old record must not enroll a replacement installation", result.needsReapply)
        assertFalse(result.retryHide)
        assertNotEquals(TargetState.NEEDS_REAPPLY_UPDATE, result.state)
    }

    @Test fun appliedEntryStillRequiresReapplyAfterAnOrdinaryUpdate() {
        val original = packageInfo()
        install(original)
        val applied = entry(identity(original), TargetState.APPLIED).copy(changedByUs = true, settlingVerified = true)
        val store = JournalStore(context)
        assertTrue(store.commit(applied))

        val updated = packageInfo(version = original.longVersionCode + 1, updated = original.lastUpdateTime + 20)
        install(updated)
        replaced()

        val result = store.entries().single()
        assertEquals(TargetState.NEEDS_REAPPLY_UPDATE, result.state)
        assertTrue(result.enabled)
        assertTrue(result.needsReapply)
    }
}
