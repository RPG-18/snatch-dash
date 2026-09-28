package com.opendash.opendash_dash_engine.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which phones get the extra warning.
 *
 * Everything else in [BatteryOptimisation] is a call into `PowerManager` or an `Intent`
 * that only a device can answer. This part is a string match, and it decides whether a
 * Huawei rider is told the thing that actually keeps their ride alive — the exemption
 * alone does not, because `PowerGenie` kills background apps independently of Doze
 * (`spec/wifi_retry_policy.md`).
 */
class BatteryOptimisationTest {

    @Test
    fun `Huawei and Honor need the extra steps`() {
        assertTrue(BatteryOptimisation.emuiWorkaroundNeeded("HUAWEI"))
        assertTrue(BatteryOptimisation.emuiWorkaroundNeeded("HONOR"))
    }

    @Test
    fun `the match survives how the field is actually spelled`() {
        // `Build.MANUFACTURER` is whatever the OEM put in the build, and on the project's
        // own test phone it reads "HUAWEI" — but neither the case nor the padding is
        // guaranteed by anything.
        for (spelling in listOf("Huawei", "huawei", " HUAWEI ", "Honor", "honor")) {
            assertTrue(BatteryOptimisation.emuiWorkaroundNeeded(spelling), spelling)
        }
    }

    @Test
    fun `other makers are not warned, including ones that merely contain the name`() {
        // A `contains` match would be tempting and wrong: the warning names a menu path
        // that does not exist on those phones, and sending a rider hunting for it is
        // worse than saying nothing.
        for (other in listOf("Xiaomi", "samsung", "Google", "", "Huaweix", "NotHuawei")) {
            assertFalse(BatteryOptimisation.emuiWorkaroundNeeded(other), other)
        }
    }
}
