package rpcnode.toolkit.panel.presentation.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnhandledErrorsTest
{
    @Test
    fun a_throwing_route_answers_json_with_the_reason_instead_of_a_bare_500()
    {
        testApplication {
            application {
                installUnhandledErrors()
                routing { get("/boom") { error("disk \"full\"") } }
            }
            val res = client.get("/boom")
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            val body = res.bodyAsText()
            assertTrue(body.contains("\"error\":\"internal_error\""), body)
            assertTrue(body.contains("disk \\\"full\\\""), body)
        }
    }
}
