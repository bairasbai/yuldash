package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxiReceiptDriverMoneyTest {
    @Test fun positiveCreditIsSeparateFromPassengerPayment() {
        assertEquals(TaxiReceiptDriverMoney(57000, 0, 1200, 58200),
            taxiReceiptDriverMoney("driver", 60000, 57000, 3000, 0, 58200, 1200))
    }

    @Test fun smallPromoReducesFeeWithoutCredit() {
        assertEquals(TaxiReceiptDriverMoney(59500, 1300, 0, 58200),
            taxiReceiptDriverMoney("driver", 60000, 59500, 500, 1300, 58200, 0))
    }

    @Test fun oldNoPromoStillWorksAndUnknownPromoDoesNotInventAmount() {
        assertEquals(TaxiReceiptDriverMoney(60000, 1800, 0, 58200),
            taxiReceiptDriverMoney("driver", 60000, 60000, 0, 1800, 58200, null))
        assertNull(taxiReceiptDriverMoney("driver", 60000, 57000, 3000, 0, 58200, null))
        assertNull(taxiReceiptDriverMoney("driver", 60000, 57000, 3000, 0, 55200, 1200))
        assertNull(taxiReceiptDriverMoney("driver", 60000, 57000, 3000, 0, 56999, -1))
    }

    @Test fun passengerCannotSeeDriverAmounts() {
        assertNull(taxiReceiptDriverMoney("passenger", 60000, 57000, 3000, 0, 58200, 1200))
    }
}
