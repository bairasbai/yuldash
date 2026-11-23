package com.yuldash.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class TaxiConsentSavedStateTest {
    @Test fun protectedNowGatesSaveAndRestoreIncludingSameFrameToggle() {
        val scheduled=mutableStateOf<Long?>(null)
        val saver=taxiConsentGatedSaver({scheduled.value==null},Saver<String,String>(save={it},restore={it}))
        val scope=SaverScope {true}
        assertNull(with(saver){scope.save("Synthetic private form")});assertNull(saver.restore("Old saved address"))
        scheduled.value=123L
        assertEquals("Scheduled",with(saver){scope.save("Scheduled")});assertEquals("Scheduled",saver.restore("Scheduled"))
        scheduled.value=null // Predicate reads state, without needing a recomposition first.
        assertNull(with(saver){scope.save("Now")});assertNull(saver.restore("Now"))
    }
    @Test fun defaultOffKeepsExistingSaverBehavior() {
        val saver=taxiConsentGatedSaver({false},Saver<String,String>(save={it},restore={it}))
        assertEquals("Legacy",with(saver){SaverScope{true}.save("Legacy")});assertEquals("Legacy",saver.restore("Legacy"))
    }
}
