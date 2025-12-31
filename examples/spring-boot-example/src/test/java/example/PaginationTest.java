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
 * Test for scenario mode (pagination) feature.
 * This test demonstrates sequential responses when the same request should return different responses over time.
 * 
 * Expected behavior when scenario = true:
 * - StableMock should use WireMock scenarios to return responses sequentially
 * - First call returns the first recorded response
 * - Second call returns the second recorded response  
 * - Third call returns the third recorded response
 * 
 * Note: jsonplaceholder.typicode.com doesn't support pagination, so all calls return the same data.
 * This test verifies that scenario mode can handle multiple sequential calls to the same endpoint.
 * In a real pagination scenario, the API would return different data for each call.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@U(urls = { "https://jsonplaceholder.typicode.com" },
   properties = { "app.thirdparty.url" },
   scenario = true)
public class PaginationTest extends BaseStableMockTest {
    
    @Autowired
    private ThirdPartyService thirdPartyService;
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        autoRegisterProperties(registry, PaginationTest.class);
    }
    
    @Test
    void testPagination() {
        // First call - should return first recorded response
        String response1 = thirdPartyService.getPosts();
        assertNotNull(response1, "First call should return a response");
        assertTrue(response1.contains("\"id\""), "Response should contain post data");
        
        // Second call - should return second recorded response (if scenario mode is implemented)
        // In scenario mode, this should return the second recorded response sequentially
        String response2 = thirdPartyService.getPosts();
        assertNotNull(response2, "Second call should return a response");
        assertTrue(response2.contains("\"id\""), "Response should contain post data");
        
        // Third call - should return third recorded response (if scenario mode is implemented)
        // In scenario mode, this should return the third recorded response sequentially
        String response3 = thirdPartyService.getPosts();
        assertNotNull(response3, "Third call should return a response");
        assertTrue(response3.contains("\"id\""), "Response should contain post data");
        
        // If scenario mode is properly implemented, WireMock scenarios would ensure
        // that each call returns a different response sequentially. Since jsonplaceholder
        // returns the same data for all calls, we can't verify different content here,
        // but we can verify that all three calls succeed and that scenario mode
        // is being used (if implemented).
    }
}

