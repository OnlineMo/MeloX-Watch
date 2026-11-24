package io.github.onlinemo.meloxwatch.data.sleep

import android.provider.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26, 30])
class AndroidSleepTimerClockTest {
    @Test fun onlyAnActualNonnegativeSystemBootCountMayIdentifyTheBoot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 17)
        assertEquals("boot-count:17", readSleepTimerBootId(context))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 18)
        assertEquals("boot-count:18", readSleepTimerBootId(context))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertNull(readSleepTimerBootId(context))
    }

    @Test fun absentBootEvidenceIsUnverifiedRatherThanInventedFromTime() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertNull(readSleepTimerBootId(context))
    }
}
