package com.component.orders

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.kafka.mock.KafkaMock
import io.specmatic.kafka.mock.model.Expectation
import io.specmatic.stub.ContractStub
import io.specmatic.stub.createStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.io.File

//@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ApiTests {
    companion object {
        private lateinit var httpStub: ContractStub
        private lateinit var kafkaMock: KafkaMock

        private const val STUB_PORT = 8090

        @BeforeAll
        @JvmStatic
        fun setUp() {
            // Start Specmatic Http Stub
            httpStub = createStub("localhost", STUB_PORT, strict = true)

            // Start kafka mock
            kafkaMock = KafkaMock.startInMemoryBroker("localhost", 9092)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            // Shutdown Specmatic Http Stub
            httpStub.close()

            // Stop kafka mock
            kafkaMock.stop()
        }
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun setExpectations(expectation: String) {
        val url = "http://localhost:$STUB_PORT/_specmatic/expectations"
        val response: ResponseEntity<String> = restTemplate.exchange(
            url,
            HttpMethod.POST,
            HttpEntity(expectation.toMap()),
            String::class.java
        )
        assert(response.statusCode == HttpStatus.OK)
    }

    @Test
    fun `should search for available products`() {
        val expectation = File("src/test/resources/stub_products_200.json").readText()
        setExpectations(expectation)

        val url = "http://localhost:8080/findAvailableProducts?type=gadget"
        val headers = HttpHeaders().apply {
            set("pageSize", "10")
        }
        val response = restTemplate.exchange(
            url,
            HttpMethod.GET,
            HttpEntity<String>(headers),
            List::class.java
        )

        assert(response.statusCode == HttpStatus.OK)

        val expectedResponse = expectation.toMap()["http-response"] as Map<String, Any>
        val expectedResponseBody = expectedResponse["body"] as List<Map<String, Any>>
        val actualResponseBody = response.body as List<Map<String, Any>>
        assertThat(actualResponseBody).isEqualTo(expectedResponseBody)
    }

    @Test
    fun `should return 503 (SERVICE_UNAVAILABLE) status if backend service is down`() {
        val expectation = File("src/test/resources/stub timeout.json").readText()
        setExpectations(expectation)
        val url = "http://localhost:8080/findAvailableProducts?type=other"
        val headers = HttpHeaders().apply {
            set("pageSize", "10")
        }
        val response = restTemplate.exchange(
            url,
            HttpMethod.GET,
            HttpEntity<String>(headers),
            String::class.java
        )

        assert(response.statusCode == HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `should create order`() {
        val expectation = """
          {
            "http-request": {
              "method": "POST",
              "path": "/orders",
              "headers": {
                "Authenticate": "API-TOKEN-SPEC"
              },
              "body": {
                "productid": 10,
                "count": 1,
                "status": "pending"
              }
            },

            "http-response": {
              "status": 200,
              "body": {
                "id": 10
              },
              "status-text": "OK"
            }
          }
        """.trimIndent()
        setExpectations(expectation)

        val response = restTemplate.exchange(
            "http://localhost:8080/orders",
            HttpMethod.POST,
            HttpEntity(mapOf("productid" to 10, "count" to 1)),
            Map::class.java
        )

        assert(response.statusCode == HttpStatus.CREATED)
        assertThat(response.body["id"] as Int).isEqualTo(10)
    }

    private fun String.toMap(): Map<*, *> {
        return ObjectMapper().readValue(this, Map::class.java)
    }
}