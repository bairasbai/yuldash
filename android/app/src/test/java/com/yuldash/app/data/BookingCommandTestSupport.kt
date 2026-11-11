package com.yuldash.app.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.robolectric.RuntimeEnvironment

internal const val COMMAND_TEST_TOKEN = "test.eyJzdWIiOiI3In0.signature"

internal fun startBookingCommandSession(server: MockWebServer) {
    ApiClient.init(RuntimeEnvironment.getApplication())
    ApiClient.saveToken(COMMAND_TEST_TOKEN)
    server.enqueue(MockResponse().setBody("""{"id":7,"created_at":"2026-01-01T00:00:00Z"}"""))
}

internal fun bookingCommandResponse(rideId: Int, status: String = "pending") = MockResponse()
    .setBody("""{"id":42,"ride_id":$rideId,"passenger_id":7,"status":"$status"}""")
