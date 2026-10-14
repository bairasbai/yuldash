package com.yuldash.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Публичные флаги: одна отменяемая попытка, общий срок на соединение и весь ответ. */
internal object FeatureFlagTransport {
    private val transport = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    fun cancelPending() = transport.dispatcher.cancelAll()

    suspend fun fetch(url: String, timeoutMs: Long): Result<JSONObject> = suspendCancellableCoroutine { waiting ->
        val call = transport.newCall(Request.Builder().url(url).get().build())
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        waiting.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { waiting.resume(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Features HTTP ${it.code}")
                        JSONObject(it.body?.string() ?: throw IOException("Empty features response"))
                    }
                }
                waiting.resume(result)
            }
        })
    }
}
