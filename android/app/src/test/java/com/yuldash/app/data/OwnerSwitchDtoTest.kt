package com.yuldash.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerSwitchDtoTest {
    @Test
    fun storedChoiceAndAvailabilityAreIndependent() {
        val switches = JSONObject(
            """{"switches":{
                "login.sms":{"stored":true,"effective":false,"blockers":["sms_atomic_budget_pending"],
                    "updated_at":null,"updated_by_name":null,"history":[]},
                "login.telegram":{"stored":null,"effective":true,"blockers":[],
                    "updated_at":null,"updated_by_name":null,"history":[]}
            }}""",
        ).toOwnerSwitches()
        val sms = switches.getValue(OwnerLoginSwitch.Sms)
        assertEquals(true, sms.stored)
        assertFalse(sms.effective)
        assertEquals(listOf("sms_atomic_budget_pending"), sms.blockers)
        assertNull(switches.getValue(OwnerLoginSwitch.Telegram).stored)
    }

    @Test
    fun confirmationRequiresExactServerRefusalAndPositiveIntegerCount() {
        fun refusal(status: Int, code: String, affected: Any) =
            ApiException(status, "fixture", code, JSONObject().put("affected", affected))
        val code = "switch_needs_confirm"
        assertEquals(17L, ownerSwitchAffected(refusal(409, code, 17)))
        assertNull(ownerSwitchAffected(refusal(409, code, "17")))
        assertNull(ownerSwitchAffected(refusal(409, code, 17.5)))
        assertNull(ownerSwitchAffected(refusal(409, code, 0)))
        assertNull(ownerSwitchAffected(refusal(403, code, 17)))
        assertNull(ownerSwitchAffected(refusal(409, "switch_blocked", 17)))
    }
}
