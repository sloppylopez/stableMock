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
    private final GraphQLClient graphQLClient;
    private final Environment environment;
    
    @Value("${app.thirdparty.url}")
    private String defaultThirdPartyUrl;

    @Autowired
    public ThirdPartyService(
            JsonPlaceholderClient jsonPlaceholderClient,
            PostmanEchoClient postmanEchoClient,
            GraphQLClient graphQLClient,
            Environment environment) {
        this.jsonPlaceholderClient = jsonPlaceholderClient;
        this.postmanEchoClient = postmanEchoClient;
        this.graphQLClient = graphQLClient;
        this.environment = environment;
    }

    public String getUser(int userId) {
        // FeignClient uses the URL from app.thirdparty.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        return jsonPlaceholderClient.getUser(userId);
    }

    public String getUserFromPostmanEcho(int userId) {
        // FeignClient uses the URL from app.postmanecho.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        return postmanEchoClient.getUser(userId);
    }

    public String postXmlToPostmanEcho(String xmlBody) {
        // FeignClient uses the URL from app.postmanecho.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        return postmanEchoClient.postXml(xmlBody);
    }

    public String createPost(String title, String body, int userId) {
        // FeignClient uses the URL from app.thirdparty.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        String requestBody = String.format(
                "{\"title\":\"%s\",\"body\":\"%s\",\"userId\":%d}",
                title, body, userId);
        return jsonPlaceholderClient.createPost(requestBody);
    }
    
    public String executeGraphQL(String requestBody) {
        // FeignClient uses the URL from app.graphql.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        return graphQLClient.executeQuery(requestBody);
    }
    
    public String getPosts() {
        // FeignClient uses the URL from app.thirdparty.url property
        // This is set by @DynamicPropertySource in tests to point to WireMock
        return jsonPlaceholderClient.getPosts();
    }
}


