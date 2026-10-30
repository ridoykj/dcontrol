package com.dcontrol.client.core.network.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.http.Body
import retrofit2.http.POST

class ApiCallTest {

    @Serializable
    data class Ping(val value: String)

    interface TestApi {
        @POST("api/ping")
        suspend fun ping(@Body body: Ping): Ping
    }

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var api: TestApi

    @Before
    fun setUp() {
        server.start()
        api = RetrofitFactory(OkHttpClient(), json).create(server.url("/").toString()).create(TestApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `problem details become ApiException with code`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(410)
                .addHeader("Content-Type", "application/problem+json")
                .body("""{"title":"Gone","status":410,"detail":"Pairing token is invalid or expired","code":"PAIRING_TOKEN_INVALID"}""")
                .build(),
        )

        try {
            apiCall(json) { api.ping(Ping("x")) }
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(410, e.status)
            assertEquals("PAIRING_TOKEN_INVALID", e.code)
            assertEquals("Pairing token is invalid or expired", e.message)
        }
    }

    @Test
    fun `successful call returns the body`() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"value":"pong"}""").build())

        assertEquals(Ping("pong"), apiCall(json) { api.ping(Ping("ping")) })
    }
}
