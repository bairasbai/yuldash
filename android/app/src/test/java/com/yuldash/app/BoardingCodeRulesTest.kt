package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила кода посадки (BoardingCodeRules.kt) без экрана — таблица «кто, когда, что видит». */
class BoardingCodeRulesTest {

    @Test fun boardingIsDoneOnLocalTapOrOnServerStatus() {
        assertFalse(boardingDone(null, "confirmed"))
        assertFalse(boardingDone(null, ""))
        assertTrue("нажала «Я сел» — ещё до ответа сервера", boardingDone("sat", "confirmed"))
        assertTrue(boardingDone("arrived", "onboard"))
        assertTrue("перезапуск: локального статуса нет, сервер знает", boardingDone(null, "onboard"))
        assertTrue(boardingDone(null, "done"))
    }

    @Test fun codeIsShownOnlyBeforeBoardingOfLiveTrip() {
        assertTrue(showBoardingCode(null, "confirmed"))
        assertTrue("статус ещё не пришёл — карточка уже с «Код загружается»", showBoardingCode(null, ""))
        assertFalse(showBoardingCode("sat", "confirmed"))
        assertFalse(showBoardingCode(null, "onboard"))
        assertFalse(showBoardingCode(null, "done"))
        assertFalse(showBoardingCode(null, "cancelled"))
        assertFalse(showBoardingCode(null, "pending"))
    }

    @Test fun offlineCopyIsForPassengerWithPassOnly() {
        assertTrue(needsOfflineCodeCopy("passenger", hasPass = true))
        assertTrue("роль ещё не пришла — не считаем водителем", needsOfflineCodeCopy("", hasPass = true))
        assertFalse("у пассажира, открывшего бронь из списка, паспорта нет", needsOfflineCodeCopy("passenger", hasPass = false))
        assertFalse(needsOfflineCodeCopy("driver", hasPass = false))
        assertFalse(needsOfflineCodeCopy("driver", hasPass = true))
        assertEquals(false, needsOfflineCodeCopy("", hasPass = false))
    }
}
