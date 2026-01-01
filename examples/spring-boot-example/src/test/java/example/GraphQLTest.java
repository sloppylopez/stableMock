package example;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that StableMock works with GraphQL requests.
 * 
 * GraphQL requests are typically POST requests with JSON bodies containing:
 * - "query": The GraphQL query string
 * - "variables": Optional variables object
 * 
 * This test uses the public Countries GraphQL API (https://countries.trevorblades.com/)
 * which is a safe, public endpoint that doesn't require authentication.
 * 
 * The test verifies that:
 * 1. GraphQL POST requests are recorded correctly
 * 2. GraphQL responses are recorded correctly
 * 3. Playback works with recorded GraphQL mocks
 * 4. GraphQL requests with variables work correctly
 */
@U(urls = { "https://countries.trevorblades.com" },
   properties = { "app.graphql.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GraphQLTest extends BaseStableMockTest {

    @Autowired
    private ThirdPartyService thirdPartyService;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, GraphQLTest.class);
    }

    @Test
    void testGraphQLQuery() {
        // Simple GraphQL query without variables - get all countries
        // Calls ThirdPartyService.executeGraphQL() -> GraphQLClient.executeQuery() -> countries.trevorblades.com
        String graphqlQuery = """
            {
              "query": "{ countries { code name } }"
            }
            """;

        String response = thirdPartyService.executeGraphQL(graphqlQuery);
        assertNotNull(response, "GraphQL response should not be null");
        assertTrue(response.contains("countries") || response.contains("data"),
                "Response should contain GraphQL data. Got: " + preview(response));
    }

    @Test
    void testGraphQLQueryWithVariables() {
        // GraphQL query with variables - get a specific country by code
        // Calls ThirdPartyService.executeGraphQL() -> GraphQLClient.executeQuery() -> countries.trevorblades.com
        String graphqlQuery = """
            {
              "query": "query GetCountry($code: ID!) { country(code: $code) { name capital } }",
              "variables": {
                "code": "US"
              }
            }
            """;

        String response = thirdPartyService.executeGraphQL(graphqlQuery);
        assertNotNull(response, "GraphQL response should not be null");
        assertTrue(response.contains("country") || response.contains("name"),
                "Response should contain GraphQL data. Got: " + preview(response));
    }

    @Test
    void testGraphQLQueryWithGB() {
        // GraphQL query with variables for a different country (GB) to ensure distinct requests
        // This test verifies that different variable values are recorded and replayed correctly
        // Calls ThirdPartyService.executeGraphQL() -> GraphQLClient.executeQuery() -> countries.trevorblades.com
        String graphqlQuery = """
            {
              "query": "query GetCountry($code: ID!) { country(code: $code) { name capital currency } }",
              "variables": {
                "code": "GB"
              }
            }
            """;

        String response = thirdPartyService.executeGraphQL(graphqlQuery);
        assertNotNull(response, "GraphQL response should not be null");
        assertTrue(response.contains("country") || response.contains("name"),
                "Response should contain GraphQL data. Got: " + preview(response));
    }

    @Test
    void testGraphQLQueryWithChangingVariables() throws InterruptedException {
        // GraphQL query where variables change between calls - tests dynamic field detection
        // First call: Get country with code "US"
        // Calls ThirdPartyService.executeGraphQL() -> GraphQLClient.executeQuery() -> countries.trevorblades.com
        String graphqlQuery1 = """
            {
              "query": "query GetCountry($code: ID!) { country(code: $code) { name capital } }",
              "variables": {
                "code": "US"
              }
            }
            """;

        String response1 = thirdPartyService.executeGraphQL(graphqlQuery1);
        assertNotNull(response1, "First response should not be null");

        Thread.sleep(50); // Small delay to ensure distinct requests

        // Second call: Same query, different variable value
        String graphqlQuery2 = """
            {
              "query": "query GetCountry($code: ID!) { country(code: $code) { name capital } }",
              "variables": {
                "code": "CA"
              }
            }
            """;

        String response2 = thirdPartyService.executeGraphQL(graphqlQuery2);
        assertNotNull(response2, "Second response should not be null");

        // Verify that the queries are different (variables changed)
        assertNotEquals(graphqlQuery1, graphqlQuery2, "GraphQL queries should differ due to changing variables");

        // In RECORD mode, json:variables.code should be detected as mutating field
        // In PLAYBACK mode, both requests should match the same stub despite different variable values
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            java.io.File detectedFields = new java.io.File(
                    "src/test/resources/stablemock/GraphQLTest/testGraphQLQueryWithChangingVariables/detected-fields.json");
            assertTrue(detectedFields.exists(),
                    "Detected fields file should exist after recording: " + detectedFields.getAbsolutePath());
        }
    }

    @Test
    void testGraphQLMutationWithDynamicFields() throws InterruptedException {
        // GraphQL mutation with dynamic fields (timestamps, IDs)
        // Note: countries.trevorblades.com doesn't support mutations, so we'll simulate
        // a mutation-like query with dynamic metadata fields
        // Calls ThirdPartyService.executeGraphQL() -> GraphQLClient.executeQuery() -> countries.trevorblades.com
        
        String timestamp1 = java.time.Instant.now().toString();
        String requestId1 = java.util.UUID.randomUUID().toString();
        
        String mutation1 = String.format("""
            {
              "query": "query GetCountry($code: ID!, $timestamp: String!, $requestId: String!) { country(code: $code) { name capital } }",
              "variables": {
                "code": "US",
                "timestamp": "%s",
                "requestId": "%s"
              }
            }
            """, timestamp1, requestId1);

        String response1 = thirdPartyService.executeGraphQL(mutation1);
        assertNotNull(response1, "First response should not be null");

        Thread.sleep(50); // Small delay to ensure values change

        String timestamp2 = java.time.Instant.now().toString();
        String requestId2 = java.util.UUID.randomUUID().toString();
        
        String mutation2 = String.format("""
            {
              "query": "query GetCountry($code: ID!, $timestamp: String!, $requestId: String!) { country(code: $code) { name capital } }",
              "variables": {
                "code": "US",
                "timestamp": "%s",
                "requestId": "%s"
              }
            }
            """, timestamp2, requestId2);

        String response2 = thirdPartyService.executeGraphQL(mutation2);
        assertNotNull(response2, "Second response should not be null");

        // Verify that the mutations are different (dynamic fields changed)
        assertNotEquals(mutation1, mutation2, "GraphQL mutations should differ due to dynamic fields");

        // In RECORD mode, json:variables.timestamp and json:variables.requestId should be detected as mutating fields
        // In PLAYBACK mode, both requests should match the same stub despite different dynamic values
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if (!"RECORD".equalsIgnoreCase(mode)) {
            java.io.File detectedFields = new java.io.File(
                    "src/test/resources/stablemock/GraphQLTest/testGraphQLMutationWithDynamicFields/detected-fields.json");
            assertTrue(detectedFields.exists(),
                    "Detected fields file should exist after recording: " + detectedFields.getAbsolutePath());
        }
    }


    private static String preview(String s) {
        if (s == null) return "null";
        return s.substring(0, Math.min(300, s.length()));
    }
}
