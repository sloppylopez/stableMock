package example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stablemock.U;
import com.stablemock.spring.BaseStableMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
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
 * The test calls the Spring Boot controller endpoint /api/posts, which goes through:
 * Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/posts
 * StableMock records the third-party API calls with their query parameters.
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
    private TestRestTemplate restTemplate;

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
        
        int page1;
        int limit1;
        String timestamp1;
        String correlationId1;
        int page2;
        int limit2;
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
            try {
                Map<String, String> params1 = readQueryParamsFromMappings("testGetWithChangingQueryParams", 1);
                Map<String, String> params2 = readQueryParamsFromMappings("testGetWithChangingQueryParams", 2);
                
                String page1Str = params1.get("page");
                String limit1Str = params1.get("limit");
                timestamp1 = params1.get("timestamp");
                correlationId1 = params1.get("correlationId");
                
                String page2Str = params2.get("page");
                String limit2Str = params2.get("limit");
                timestamp2 = params2.get("timestamp");
                correlationId2 = params2.get("correlationId");
                
                // Validate that we got all required parameters BEFORE parsing ints
                if (page1Str == null || limit1Str == null || timestamp1 == null || correlationId1 == null ||
                    page2Str == null || limit2Str == null || timestamp2 == null || correlationId2 == null) {
                    throw new RuntimeException("Failed to read all required query parameters from mappings. " +
                            "params1: " + params1 + ", params2: " + params2);
                }
                
                page1 = Integer.parseInt(page1Str);
                limit1 = Integer.parseInt(limit1Str);
                
                page2 = Integer.parseInt(page2Str);
                limit2 = Integer.parseInt(limit2Str);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read query parameters from mappings in playback mode. " +
                        "Make sure mappings exist and were recorded correctly. Error: " + e.getMessage(), e);
            }
        }
        
        // First call: Call Spring Boot controller endpoint with query parameters
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/posts?page=1&limit=10&timestamp=...&correlationId=...
        String url1 = "/api/posts?page=" + page1 + "&limit=" + limit1 + "&timestamp=" + timestamp1 + "&correlationId=" + correlationId1;
        ResponseEntity<String> response1Entity = restTemplate.getForEntity(url1, String.class);
        String response1 = response1Entity.getBody();
        assertNotNull(response1, "First response should not be null");
        // Note: jsonplaceholder.typicode.com/posts returns an array, so check for array format
        assertTrue(response1.contains("[") || response1.contains("\"id\""), 
                "Response should contain post data. Got: " + preview(response1));

        Thread.sleep(50); // Small delay

        // Second call: Same controller endpoint, different query parameter values
        // Flow: Test -> Controller -> ThirdPartyService -> JsonPlaceholderClient -> jsonplaceholder.typicode.com/posts?page=2&limit=10&timestamp=...&correlationId=...
        String url2 = "/api/posts?page=" + page2 + "&limit=" + limit2 + "&timestamp=" + timestamp2 + "&correlationId=" + correlationId2;
        ResponseEntity<String> response2Entity = restTemplate.getForEntity(url2, String.class);
        String response2 = response2Entity.getBody();
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
            // Try multiple possible locations for mappings
            File mappingsDir = null;
            
            // First, try test method directory (original location)
            File testMethodDir = new File("src/test/resources/stablemock/QueryParametersDynamicFieldsTest/" + 
                    testMethodName + "/mappings");
            File classLevelDir = new File("src/test/resources/stablemock/QueryParametersDynamicFieldsTest/mappings");
            
            if (testMethodDir.exists()) {
                mappingsDir = testMethodDir;
            } else if (classLevelDir.exists()) {
                // Try class-level merged directory (playback mode)
                mappingsDir = classLevelDir;
            } else {
                // Try absolute path from class loader
                java.net.URL resource = QueryParametersDynamicFieldsTest.class.getClassLoader()
                        .getResource("stablemock/QueryParametersDynamicFieldsTest/" + testMethodName + "/mappings");
                if (resource != null) {
                    mappingsDir = new File(resource.getFile());
                }
            }
            
            if (mappingsDir == null || !mappingsDir.exists()) {
                throw new RuntimeException("Mappings directory not found. Tried: " + 
                        testMethodDir.getAbsolutePath() + ", " + classLevelDir.getAbsolutePath());
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


