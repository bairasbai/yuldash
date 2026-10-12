package com.yuldash.app

import android.util.Log
import com.yuldash.app.data.TestContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Actual Android API transport and EOF, no UI/mock mutation shortcut. */
@RunWith(AndroidJUnit4::class)
class ContactsTransportInstrumentedTest {
    private lateinit var server: Loopback
    @Before fun setup() {
        ApiClient.testTrace=null; ApiClient.resetForTest()
        ApiClient.init(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        server=Loopback(); ApiClient.testBaseUrl=server.url; ApiClient.testTimeoutMs=2000
    }
    @After fun cleanup() { server.close(); ApiClient.testTrace=null; ApiClient.resetForTest(); ApiClient.testTimeoutMs=null }
    private fun eof(add: Boolean, commit: Boolean) = runBlocking {
        server.row=!add; server.commit=commit
        val before=TestContacts.changes
        assertEquals(if(add) emptyList<Int>() else listOf(7),TestContacts.read().getOrThrow().map { it.id })
        val result=if(add) TestContacts.add("Әсәй — контакт","Синтетический","+70000000007",false) else TestContacts.delete(7)
        assertTrue(result.isFailure); assertTrue(com.yuldash.app.data.ContactsApi.isUncertain(result.exceptionOrNull()))
        assertEquals("A sent mutation must not replay after EOF",1,server.mutations.get())
        assertEquals(before+1,TestContacts.changes)
        assertEquals(if(if(commit) add else !add) listOf(7) else emptyList<Int>(),TestContacts.read().getOrThrow().map { it.id })
        assertEquals(2,server.gets.get()); assertTrue(server.errors.toString(),server.errors.isEmpty())
        val sent=server.records.single { it.method==if(add) "POST" else "DELETE" }
        assertEquals("Bearer header.eyJzdWIiOiIxMSJ9.signature",sent.bearer)
        if(add) {
            val body=JSONObject(sent.body.toString(Charsets.UTF_8)); assertEquals(4,body.length())
            assertEquals("Әсәй — контакт",body.getString("name")); assertEquals("Синтетический",body.getString("relation"))
            assertEquals("+70000000007",body.getString("phone")); assertFalse(body.getBoolean("notify_by_default"))
        } else assertEquals(0,sent.body.size)
        Log.i("B02ContactsTransport","verified add=$add commit=$commit actualAndroidHttp=true mutations=1 freshGets=2 failurePreserved=true")
        Unit
    }
    @Test fun deleteEofAfterCommit()=eof(false,true)
    @Test fun addEofAfterCommit()=eof(true,true)
    @Test fun deleteEofBeforeCommit()=eof(false,false)
    @Test fun addEofBeforeCommit()=eof(true,false)
    private fun successful(add:Boolean)=runBlocking {
        server.drop=false
        val result=if(add) TestContacts.add("Әсәй — контакт","Синтетический","+70000000007",false) else TestContacts.delete(7)
        result.getOrThrow(); assertEquals(1,server.mutations.get())
        assertEquals(if(add) listOf(7) else emptyList<Int>(),TestContacts.read().getOrThrow().map { it.id })
    }
    @Test fun successfulAdd()=successful(true)
    @Test fun successfulDelete()=successful(false)
    private fun rejected(add:Boolean,redirect:Boolean)=runBlocking {
        server.drop=false; server.replyCode=if(redirect) 307 else 503
        val result=if(add) TestContacts.add("Synthetic","","+70000000007",true) else TestContacts.delete(7)
        val error=result.exceptionOrNull(); assertTrue(error is ApiException); assertEquals(server.replyCode,(error as ApiException).status)
        assertEquals(1,server.mutations.get()); assertEquals(1,server.records.size); assertFalse(server.row)
    }
    @Test fun add503RetryAfterZeroDoesNotReplay()=rejected(true,false)
    @Test fun delete503RetryAfterZeroDoesNotReplay()=rejected(false,false)
    @Test fun addRedirectDoesNotReplay()=rejected(true,true)
    @Test fun deleteRedirectDoesNotReplay()=rejected(false,true)
    private fun refreshed(add:Boolean)=runBlocking {
        ApiClient.verifyCode("+70000000000","000000","Synthetic").getOrThrow(); server.refreshRequired=true
        val owner=ApiClient.queueSessionGeneration()
        val result=if(add) TestContacts.add("Synthetic","","+70000000007",true) else TestContacts.delete(7)
        assertTrue(result.isFailure); assertTrue(com.yuldash.app.data.ContactsApi.isUncertain(result.exceptionOrNull()))
        assertEquals(2,server.mutations.get()); assertEquals(1,server.records.count { it.path=="/auth/refresh" })
        val sent=server.records.filter { it.path==(if(add) "/trusted-contacts" else "/trusted-contacts/7") }
        assertEquals(listOf("Bearer old-access","Bearer new-access"),sent.map { it.bearer })
        assertEquals("new-access",ApiClient.currentToken()); assertEquals(owner,ApiClient.queueSessionGeneration())
    }
    @Test fun refreshedAddEofDoesNotReplay()=refreshed(true)
    @Test fun refreshedDeleteEofDoesNotReplay()=refreshed(false)
    private data class Record(val method:String,val path:String,val bearer:String?,val body:ByteArray)
    private class Loopback:AutoCloseable {
        private val listener=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))
        private val workers=Executors.newCachedThreadPool { action -> Thread(action,"contacts-transport-http").apply { isDaemon=true } }
        private val sockets=CopyOnWriteArrayList<Socket>()
        val records=CopyOnWriteArrayList<Record>(); val errors=CopyOnWriteArrayList<String>()
        val mutations=AtomicInteger(); val gets=AtomicInteger()
        @Volatile var row=false; @Volatile var commit=true; @Volatile var drop=true
        @Volatile var replyCode=200; @Volatile var refreshRequired=false; @Volatile private var closed=false
        val url="http://127.0.0.1:${listener.localPort}"
        private val accept=thread(isDaemon=true,name="contacts-transport-accept") {
            while(!closed) {
                val socket=runCatching { listener.accept() }.getOrNull()?:break
                sockets+=socket; workers.submit {
                    try { respond(socket) } catch(error:Exception) { if(!closed) errors+=error.toString() }
                    finally { sockets.remove(socket) }
                }
            }
        }
        private fun respond(socket:Socket) = socket.use {
            socket.soTimeout=10000; val input=socket.getInputStream().buffered()
            fun line():String {
                val bytes=ArrayList<Byte>()
                while(true) { val n=input.read(); check(n>=0); if(n==10) break; if(n!=13) bytes+=n.toByte() }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            val first=line().split(' '); var length=0; var bearer:String?=null
            while(true) {
                val header=line(); if(header.isEmpty()) break
                if(header.startsWith("Content-Length:",true)) length=header.substringAfter(':').trim().toInt()
                if(header.startsWith("Authorization:",true)) bearer=header.substringAfter(':').trim()
            }
            val body=input.readNBytes(length); check(body.size==length)
            records+=Record(first[0],first[1],bearer,body)
            fun reply(code:Int,text:String,extra:String="") {
                val bytes=text.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $code Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(bytes); flush()
                }
            }
            when {
                first[1]=="/auth/verify" -> reply(200,"""{"access_token":"old-access","refresh_token":"old-refresh"}""")
                first[1]=="/auth/refresh" -> reply(200,"""{"access_token":"new-access","refresh_token":"new-refresh"}""")
                first[0]=="GET" && first[1]=="/trusted-contacts" -> {
                    gets.incrementAndGet()
                    val text=if(row) """{"items":[{"id":7,"name":"Synthetic","phone":"+70000000007"}]}""" else """{"items":[]}"""
                    reply(200,text)
                }
                (first[0]=="POST" && first[1]=="/trusted-contacts") || (first[0]=="DELETE" && first[1]=="/trusted-contacts/7") -> {
                    mutations.incrementAndGet()
                    if(refreshRequired && bearer!="Bearer new-access") { reply(401,"{}"); return@use }
                    if(replyCode!=200) {
                        reply(replyCode,"{}",if(replyCode==307) "Location: $url/redirect-target\r\n" else "Retry-After: 0\r\n"); return@use
                    }
                    if(commit) row=first[0]=="POST"
                    // The request was consumed and optionally committed; close without an HTTP reply.
                    if(!drop) reply(200,"{\"ok\":true}")
                }
                else -> error("Unexpected request ${first[0]} ${first[1]}")
            }
        }
        override fun close() { closed=true; listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow(); accept.join(2000) }
    }
}
