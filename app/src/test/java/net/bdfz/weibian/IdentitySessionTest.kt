package net.bdfz.weibian

import com.sun.net.httpserver.HttpServer
import net.bdfz.weibian.network.ApiClient
import net.bdfz.weibian.network.ApiException
import net.bdfz.weibian.security.AppSession
import net.bdfz.weibian.ui.SessionValidationState
import net.bdfz.weibian.ui.resolveStoredSessionValidation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class IdentitySessionTest {
    @Test
    fun `login requires canonical user slug and does not fall back to typed username`() {
        var calls = 0
        val api = apiResponding { request ->
            calls++
            response(
                request,
                200,
                """{"ok":true}""",
                setCookie = true,
            )
        }

        assertThrows(ApiException::class.java) {
            api.login("TypedUsername", "not-persisted")
        }
        assertEquals(1, calls)
    }

    @Test
    fun `successful login is validated through me before returning canonical session`() {
        val requests = mutableListOf<Request>()
        val api = apiResponding { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/login" -> response(
                    request,
                    200,
                    """{"ok":true,"user":{"slug":"reader-1","displayName":"Login Name"}}""",
                    setCookie = true,
                )
                "/api/me" -> response(
                    request,
                    200,
                    """{"slug":"reader-1","displayName":"Canonical Name"}""",
                )
                else -> error("unexpected request")
            }
        }

        val session = api.login("TypedUsername", "not-persisted")

        assertEquals("reader-1", session.slug)
        assertEquals("Canonical Name", session.displayName)
        assertEquals("bdfz_uc_session=opaque-session", session.cookie)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals("/api/me", requests.last().url.encodedPath)
        assertEquals(session.cookie, requests.last().header("Cookie"))
        assertFalse(session.toString().contains("not-persisted"))
    }

    @Test
    fun `login fails closed when me resolves cookie to another canonical slug`() {
        val api = apiResponding { request ->
            when (request.url.encodedPath) {
                "/api/login" -> response(
                    request,
                    200,
                    """{"ok":true,"user":{"slug":"reader-1"}}""",
                    setCookie = true,
                )
                "/api/me" -> response(request, 200, """{"slug":"reader-2"}""")
                else -> error("unexpected request")
            }
        }

        val error = assertThrows(ApiException::class.java) {
            api.login("reader-1", "not-persisted")
        }
        assertEquals(409, error.status)
    }

    @Test
    fun `expired cold start clears persisted identity and requires authentication`() {
        val resolution = resolveStoredSessionValidation(
            STORED,
            Result.failure(ApiException("expired", status = 401)),
        )

        assertNull(resolution.session)
        assertEquals(SessionValidationState.AUTH_REQUIRED, resolution.validationState)
        assertTrue(resolution.clearPersistedSession)
    }

    @Test
    fun `offline cold start retains local identity but marks it unverified`() {
        val resolution = resolveStoredSessionValidation(
            STORED,
            Result.failure(IOException("offline")),
        )

        assertSame(STORED, resolution.session)
        assertEquals(
            SessionValidationState.OFFLINE_UNVERIFIED,
            resolution.validationState,
        )
        assertFalse(resolution.clearPersistedSession)
    }

    @Test
    fun `authenticated request never follows a redirect to another host`() {
        val redirectedCalls = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/capture") { exchange ->
            redirectedCalls.incrementAndGet()
            val bytes = """{"slug":"reader-1"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        origin.createContext("/api/me") { exchange ->
            exchange.responseHeaders.add(
                "Location",
                "http://127.0.0.1:${target.address.port}/capture",
            )
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(302, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        target.start()
        origin.start()
        try {
            val api = ApiClient(
                userCenterUrl = "http://127.0.0.1:${origin.address.port}",
            )

            val error = assertThrows(ApiException::class.java) {
                api.validateSession(STORED)
            }

            assertEquals(302, error.status)
            assertEquals(0, redirectedCalls.get())
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }

    private fun apiResponding(
        responder: (Request) -> Response,
    ): ApiClient {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> responder(chain.request()) }
            .build()
        return ApiClient(
            userCenterUrl = "https://user-center.test",
            client = client,
        )
    }

    private fun response(
        request: Request,
        status: Int,
        body: String,
        setCookie: Boolean = false,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("test")
        .apply {
            if (setCookie) {
                addHeader(
                    "Set-Cookie",
                    "bdfz_uc_session=opaque-session; Path=/; HttpOnly; Secure",
                )
            }
        }
        .body(body.toResponseBody(JSON))
        .build()

    private companion object {
        val STORED = AppSession(
            slug = "reader-1",
            displayName = "Reader",
            cookie = "bdfz_uc_session=opaque-session",
        )
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
