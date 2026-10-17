package com.yuldash.app

import okhttp3.OkHttpClient
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * HUNT ONLY (disposable branch): an OkHttp client whose socket pauses [delayMs] right before it writes a
 * WebSocket close frame (first byte 0x88). It holds OkHttp's writer between "close frame dequeued" and
 * "close frame written" — the window in which the reader may already treat the close handshake as done.
 */
object HuntSlowClose {
    fun client(delayMs: Long): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun getOutputStream(): OutputStream = object : FilterOutputStream(super.getOutputStream()) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (len > 0 && b[off] == 0x88.toByte()) Thread.sleep(delayMs)
                        out.write(b, off, len)
                    }
                }
            }
            override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        })
        .build()
}
