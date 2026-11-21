package com.yuldash.app.data

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

// A NEW client per request, not newBuilder() of a shared one: OkHttp keeps "failed" addresses in the
// client's route database, so one lost reply sent every later mutation to the other address (IPv6)
// with no fallback. The pool keeps no idle socket: a server-closed one must not eat an explicit retry.
internal fun oneShotTransport(connectMs: Int, readMs: Int, totalTimeoutMs: Long): OkHttpClient =
    OkHttpClient.Builder().retryOnConnectionFailure(false)
    .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
    .followRedirects(false).followSslRedirects(false)
    .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
    .callTimeout(totalTimeoutMs, TimeUnit.MILLISECONDS).connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
    .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS).writeTimeout(readMs.toLong(), TimeUnit.MILLISECONDS).build()

internal fun oneShotBody(
    json: String?,
    admitWire: (() -> Boolean)?,
    claimBody: (() -> Boolean)?,
): RequestBody {
    val bytes = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
    return object : RequestBody() {
        override fun contentType() = if (json != null) "application/json; charset=utf-8".toMediaType() else null
        override fun contentLength() = bytes.size.toLong()
        override fun isOneShot() = true // Also for empty DELETE: 503/408 follow-ups must not resend it.

        override fun writeTo(sink: BufferedSink) {
            if (admitWire?.invoke() == false) throw IOException("Command session ended before send")
            if (claimBody != null && (!claimBody() || admitWire?.invoke() == false)) {
                throw IOException("Command could not record send attempt")
            }
            sink.write(bytes)
        }
    }
}
