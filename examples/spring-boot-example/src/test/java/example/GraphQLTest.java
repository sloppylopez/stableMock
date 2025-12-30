package example;

import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
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
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, GraphQLTest.class);
    }

    @Test
    void testGraphQLQuery() {
        // Simple GraphQL query without variables - get all countries
        String graphqlQuery = """
            {
              "query": "{ countries { code name } }"
            }
            """;

        ResponseEntity<String> response = postGraphQL("/api/graphql", graphqlQuery);
        assertResponseOkOrFailWithInstructions(response);

        assertNotNull(response.getBody(), "GraphQL response should not be null");
        assertTrue(response.getBody().contains("countries") || response.getBody().contains("data"),
                "Response should contain GraphQL data. Got: " + preview(response.getBody()));
    }

    @Test
    void testGraphQLQueryWithVariables() {
        // GraphQL query with variables - get a specific country by code
        String graphqlQuery = """
            {
              "query": "query GetCountry($code: ID!) { country(code: $code) { name capital } }",
              "variables": {
                "code": "US"
              }
            }
            """;

        ResponseEntity<String> response = postGraphQL("/api/graphql", graphqlQuery);
        assertResponseOkOrFailWithInstructions(response);

        assertNotNull(response.getBody(), "GraphQL response should not be null");
        assertTrue(response.getBody().contains("country") || response.getBody().contains("name"),
                "Response should contain GraphQL data. Got: " + preview(response.getBody()));
    }

    @Test
    void testGraphQLQueryWithDynamicVariables() {
        // GraphQL query with variables that change between runs (to test variable ignoring)
        // Note: GraphQL variable ignoring (gql:variables.fieldName) is documented but not yet implemented
        // For now, this test verifies basic GraphQL recording/replay works
        String graphqlQuery = """
            {
              "query": "query GetCountry($code: ID!) { country(code: $code) { name capital currency } }",
              "variables": {
                "code": "GB"
              }
            }
            """;

        ResponseEntity<String> response = postGraphQL("/api/graphql", graphqlQuery);
        assertResponseOkOrFailWithInstructions(response);

        assertNotNull(response.getBody(), "GraphQL response should not be null");
        assertTrue(response.getBody().contains("country") || response.getBody().contains("name"),
                "Response should contain GraphQL data. Got: " + preview(response.getBody()));
    }

    private ResponseEntity<String> postGraphQL(String path, String graphqlBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(graphqlBody, headers);
        return restTemplate.exchange(path, HttpMethod.POST, entity, String.class);
    }

    private void assertResponseOkOrFailWithInstructions(ResponseEntity<String> response) {
        int status = response.getStatusCodeValue();
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        if ("RECORD".equalsIgnoreCase(mode)) {
            assertEquals(200, status, "Request should succeed in RECORD mode. Status: " + status);
        } else {
            if (status != 200) {
                fail(String.format(
                        "Request failed in PLAYBACK mode! Status: %d, Body: %s%n" +
                                "STEP 1: Run record twice: ./gradlew stableMockRecord%n" +
                                "STEP 2: Then run playback: ./gradlew stableMockPlayback%n",
                        status, preview(response.getBody())));
            }
        }
    }

    private static String preview(String s) {
        if (s == null) return "null";
        return s.substring(0, Math.min(300, s.length()));
    }
}
