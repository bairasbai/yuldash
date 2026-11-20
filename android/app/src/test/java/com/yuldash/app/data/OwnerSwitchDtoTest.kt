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
                "login.sms":{"stored":true,"effective":false,"blockers":["smsru_confirmation_pending"],
                    "updated_at":null,"updated_by_name":null,"history":[],"in_flight":3},
                "login.telegram":{"stored":null,"effective":true,"blockers":[],
                    "updated_at":null,"updated_by_name":null,"history":[]}
            }}""",
        ).toOwnerSwitches()
        val sms = switches.getValue(OwnerLoginSwitch.Sms)
        assertEquals(true, sms.stored)
        assertFalse(sms.effective)
        assertEquals(listOf("smsru_confirmation_pending"), sms.blockers)
        assertEquals(3L, sms.inFlight)
        assertNull(switches.getValue(OwnerLoginSwitch.Telegram).stored)
        assertNull(switches.getValue(OwnerLoginSwitch.Telegram).inFlight)
    }

    @Test
    fun inFlightAcceptsOnlyNonnegativeSafeIntegers() {
        val row = JSONObject("""{"stored":false,"effective":false,"blockers":[],"history":[]}""")
        assertEquals(0L, row.put("in_flight", 0).toOwnerSwitch().inFlight)
        for (invalid in listOf(-1, 1.5, "3", JSONObject.NULL, 9_007_199_254_740_992L)) {
            assertNull(row.put("in_flight", invalid).toOwnerSwitch().inFlight)
        }
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
