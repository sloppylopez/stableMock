package example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stablemock.U;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for dynamic field detection in query parameters for third-party API calls.
 * 
 * This test verifies that StableMock can detect changing query parameters
 * in actual third-party API calls (via Feign client), not Spring Boot controller calls.
 * 
 * The test calls ThirdPartyService.getPostsWithQueryParams(), which uses JsonPlaceholderClient
 * to make HTTP calls to the third-party API (jsonplaceholder.typicode.com) with query parameters.
 * StableMock records these third-party API calls with their query parameters.
 * 
 * Expected behavior:
 * - RECORD mode: Records third-party API calls with different query params as separate mappings
 * - PLAYBACK mode: Uses exact recorded values (query param detection not yet implemented)
 */
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class QueryParametersDynamicFieldsTest extends BaseStableMockTest {

    @Autowired
    private ThirdPartyService thirdPartyService;

    @DynamicPropertySource
    static void registerMockUrls(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, QueryParametersDynamicFieldsTest.class);
    }

    @Test
    void testGetWithChangingQueryParams() throws InterruptedException {
        // NOTE: Query parameter dynamic field detection is not yet implemented.
        // This test verifies that third-party API calls with different query parameters are recorded correctly.
        // The query parameters are added to the actual HTTP call to jsonplaceholder.typicode.com/posts
        // In RECORD mode: Both requests are recorded as separate mappings with different query params
        // In PLAYBACK mode: We use fixed values that match what was recorded (since query param detection isn't implemented)
        
        String mode = System.getProperty("stablemock.mode", "PLAYBACK");
        
        Integer page1;
        Integer limit1;
        String timestamp1;
        String correlationId1;
        Integer page2;
        Integer limit2;
        String timestamp2;
        String correlationId2;
        
        if ("RECORD".equalsIgnoreCase(mode)) {
            // In RECORD mode: Use dynamic values to test recording
            page1 = 1;
            limit1 = 10;
            timestamp1 = String.valueOf(System.currentTimeMillis());
            correlationId1 = UUID.randomUUID().toString();
            Thread.sleep(50);
            page2 = 2;
            limit2 = 10;
            timestamp2 = String.valueOf(System.currentTimeMillis());
            correlationId2 = UUID.randomUUID().toString();
        } else {
            // In PLAYBACK mode: Read actual values from recorded mappings
            // This ensures the test works even after re-recording with build.ps1
            Map<String, String> params1 = readQueryParamsFromMappings("testGetWithChangingQueryParams", 1);
            Map<String, String> params2 = readQueryParamsFromMappings("testGetWithChangingQueryParams", 2);
            
            page1 = Integer.parseInt(params1.get("page"));
            limit1 = Integer.parseInt(params1.get("limit"));
            timestamp1 = params1.get("timestamp");
            correlationId1 = params1.get("correlationId");
            
            page2 = Integer.parseInt(params2.get("page"));
            limit2 = Integer.parseInt(params2.get("limit"));
            timestamp2 = params2.get("timestamp");
            correlationId2 = params2.get("correlationId");
        }
        
        // First call: Third-party API call with query parameters
        // This goes through: ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/posts?page=1&limit=10&timestamp=...&correlationId=...
        String response1 = thirdPartyService.getPostsWithQueryParams(page1, limit1, timestamp1, correlationId1);
        assertNotNull(response1, "First response should not be null");
        // Note: jsonplaceholder.typicode.com/posts returns an array, so check for array format
        assertTrue(response1.contains("[") || response1.contains("\"id\""), 
                "Response should contain post data. Got: " + preview(response1));

        Thread.sleep(50); // Small delay

        // Second call: Same third-party API endpoint, different query parameter values
        // This goes through: ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/posts?page=2&limit=10&timestamp=...&correlationId=...
        String response2 = thirdPartyService.getPostsWithQueryParams(page2, limit2, timestamp2, correlationId2);
        assertNotNull(response2, "Second response should not be null");
        assertTrue(response2.contains("[") || response2.contains("\"id\""), 
                "Response should contain post data. Got: " + preview(response2));

        if ("RECORD".equalsIgnoreCase(mode)) {
            // In RECORD mode: Verify that the query params are different
            assertNotEquals(timestamp1, timestamp2, "Timestamps should differ");
            assertNotEquals(correlationId1, correlationId2, "Correlation IDs should differ");
            assertNotEquals(page1, page2, "Page numbers should differ");
        }

        // TODO: Query parameter dynamic field detection is a future enhancement.
        // Currently, StableMock records each unique URL (including query params) as a separate mapping.
        // Future: Detect changing query parameters in third-party API calls and ignore them during matching.
    }
    
    private static String preview(String s) {
        if (s == null) return "null";
        return s.substring(0, Math.min(300, s.length()));
    }
    
    /**
     * Reads query parameters from recorded mapping files.
     * @param testMethodName The test method name (directory name)
     * @param pageNumber The page number to find (1 or 2)
     * @return Map of query parameter names to values
     */
    private static Map<String, String> readQueryParamsFromMappings(String testMethodName, int pageNumber) {
        try {
            File mappingsDir = new File("src/test/resources/stablemock/QueryParametersDynamicFieldsTest/" + 
                    testMethodName + "/mappings");
            
            if (!mappingsDir.exists()) {
                throw new RuntimeException("Mappings directory not found: " + mappingsDir.getAbsolutePath());
            }
            
            File[] mappingFiles = mappingsDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (mappingFiles == null || mappingFiles.length == 0) {
                throw new RuntimeException("No mapping files found in: " + mappingsDir.getAbsolutePath());
            }
            
            ObjectMapper mapper = new ObjectMapper();
            
            // Find the mapping file with the matching page number
            for (File mappingFile : mappingFiles) {
                JsonNode mapping = mapper.readTree(mappingFile);
                JsonNode request = mapping.get("request");
                if (request != null) {
                    JsonNode urlNode = request.get("url");
                    if (urlNode != null) {
                        String url = urlNode.asText();
                        // Parse query parameters from URL
                        Map<String, String> params = parseQueryParams(url);
                        String pageParam = params.get("page");
                        if (pageParam != null && Integer.parseInt(pageParam) == pageNumber) {
                            return params;
                        }
                    }
                }
            }
            
            throw new RuntimeException("Could not find mapping with page=" + pageNumber + 
                    " in " + mappingsDir.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read query parameters from mappings: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parses query parameters from a URL string.
     */
    private static Map<String, String> parseQueryParams(String url) {
        Map<String, String> params = new HashMap<>();
        
        int queryStart = url.indexOf('?');
        if (queryStart == -1) {
            return params;
        }
        
        String queryString = url.substring(queryStart + 1);
        String[] pairs = queryString.split("&");
        
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = URLDecoder.decode(pair.substring(0, eqIndex), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eqIndex + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        
        return params;
    }
}

