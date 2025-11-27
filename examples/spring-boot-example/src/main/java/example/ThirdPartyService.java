package example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;

@Service
public class ThirdPartyService {

    private final JsonPlaceholderClient jsonPlaceholderClient;
    private final PostmanEchoClient postmanEchoClient;
    private final Environment environment;
    
    @Value("${app.thirdparty.url}")
    private String defaultThirdPartyUrl;

    @Autowired
    public ThirdPartyService(JsonPlaceholderClient jsonPlaceholderClient, PostmanEchoClient postmanEchoClient, Environment environment) {
        this.jsonPlaceholderClient = jsonPlaceholderClient;
        this.postmanEchoClient = postmanEchoClient;
        this.environment = environment;
    }

    public String getUser(int userId) {
        // FeignClient uses the URL from app.thirdparty.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        return jsonPlaceholderClient.getUser(userId);
    }

    public String getUserFromReqRes(int userId) {
        // FeignClient uses the URL from app.reqres.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        return postmanEchoClient.getUser(userId);
    }

    public String createPost(String title, String body, int userId) {
        // FeignClient uses the URL from app.thirdparty.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        String requestBody = String.format(
                "{\"title\":\"%s\",\"body\":\"%s\",\"userId\":%d}",
                title, body, userId);
        return jsonPlaceholderClient.createPost(requestBody);
    }
    
    private String getThreadLocalBaseUrl() {
        try {
            Class<?> wireMockContextClass = Class.forName("com.stablemock.WireMockContext");
            java.lang.reflect.Method method = wireMockContextClass.getMethod("getThreadLocalBaseUrl");
            return (String) method.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }
}


