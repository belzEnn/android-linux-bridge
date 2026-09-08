package io.github.belzenn.androidlinuxbridge

import io.github.belzenn.androidlinuxbridge.ui.SetupStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {
    @Test fun batteryOptimizationDoesNotRequirePermissionSetup() {
        assertTrue(SetupStatus(true, true, true, false).permissionsGranted)
        assertTrue(SetupStatus(true, true, true, true).permissionsGranted)
    }

    @Test fun missingRequiredPermissionStillRequiresSetup() {
        assertFalse(SetupStatus(false, true, true, true).permissionsGranted)
        assertFalse(SetupStatus(true, false, true, true).permissionsGranted)
        assertFalse(SetupStatus(true, true, false, true).permissionsGranted)
    }
}
