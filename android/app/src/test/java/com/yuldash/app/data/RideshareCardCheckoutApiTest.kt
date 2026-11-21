package com.yuldash.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideshareCardCheckoutApiTest {
    @Test fun checkoutAcceptsOnlyTheCanonicalBankAuthority() {
        assertTrue(trustedCardCheckoutUrl("https://yoomoney.ru/checkout/synthetic"))
        assertTrue(trustedCardCheckoutUrl("https://yookassa.ru:443/checkout/synthetic"))
        listOf(
            "http://yoomoney.ru/checkout",
            "https://yoomoney.ru.invalid/checkout",
            "https://user@yoomoney.ru/checkout",
            "https://yoomoney.ru@other.example",
            "https://yoomoney.ru:444/checkout",
            "https://yoomoney.ru:/checkout",
            "https://yoomoney.ru\\@other.example",
            "https://%79oomoney.ru/checkout",
            "https://yoomoney.ru./checkout",
            " https://yoomoney.ru/checkout",
        ).forEach { assertFalse(it, trustedCardCheckoutUrl(it)) }
    }
}
