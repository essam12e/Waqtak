package com.waqtak.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoNetworkTest {
    @Test
    fun appDoesNotRequestInternetOrPrivateDataPermissions() {
        val ctx = DeviceUtils.ctx
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions.orEmpty().toSet()
        DeviceUtils.log("permissions", perms.sorted().joinToString("\n"))
        listOf(
            Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO,
        ).forEach { assertFalse("$it must not be requested", it in perms) }
        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in perms)
    }

    @Test
    fun airplaneModeIsOnDuringDeviceTests() {
        val v = DeviceUtils.shell("settings get global airplane_mode_on").trim()
        DeviceUtils.log("airplane", "airplane_mode_on=$v")
        assertTrue("airplane mode should be enabled by the test script", v == "1")
    }
}
