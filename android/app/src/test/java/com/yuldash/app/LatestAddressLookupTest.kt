package com.yuldash.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestAddressLookupTest {
    @Test fun lateOldResponseCannotReplaceNewMapSelection() = runBlocking {
        val resolver = LatestAddressLookup(this)
        val oldResponse = CompletableDeferred<String>()
        var label = ""
        // Даже транспорт, который не прерывается сразу при отмене, не вправе менять новый выбор.
        val old = resolver.resolve({ withContext(NonCancellable) { oldResponse.await() } }) { label = it }
        yield()
        resolver.resolve({ "Новая точка" }) { label = it }.join()
        oldResponse.complete("Старая точка")
        old.join()
        assertEquals("Новая точка", label)
    }

    @Test fun savedAddressStaysSelectedAfterOldMapResponse() = runBlocking {
        val resolver = LatestAddressLookup(this)
        val response = CompletableDeferred<String>()
        var label = "Определяем адрес"
        val old = resolver.resolve({ withContext(NonCancellable) { response.await() } }) { label = it }
        yield()
        resolver.cancel()
        label = "Дом"
        response.complete("Прежняя точка на карте")
        old.join()
        assertEquals("Дом", label)
    }

    @Test fun pickupAndDestinationResolveIndependently() = runBlocking {
        val pickup = LatestAddressLookup(this)
        val destination = LatestAddressLookup(this)
        val pickupResponse = CompletableDeferred<String>()
        var from = ""
        var to = ""
        val fromJob = pickup.resolve({ pickupResponse.await() }) { from = it }
        yield()
        destination.resolve({ "Первая точка Б" }) { to = it }.join()
        destination.resolve({ "Вторая точка Б" }) { to = it }.join()
        pickupResponse.complete("Точка А")
        fromJob.join()
        assertEquals("Точка А", from)
        assertEquals("Вторая точка Б", to)
    }

    @Test fun leavingScreenPreventsLateLabelPublication() = runBlocking {
        val screenScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val resolver = LatestAddressLookup(screenScope)
        val response = CompletableDeferred<String>()
        var publications = 0
        val request = resolver.resolve({ withContext(NonCancellable) { response.await() } }) { publications++ }
        yield()
        screenScope.cancel()
        response.complete("Ответ после ухода с экрана")
        request.join()
        assertEquals(0, publications)
    }
}
