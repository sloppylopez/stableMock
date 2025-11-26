package example;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;

@ApplicationScoped
public class ThirdPartyService {

    @ConfigProperty(name = "app.thirdparty.url", defaultValue = "https://jsonplaceholder.typicode.com")
    String defaultThirdPartyUrl;

    public String getUser(int userId) {
        JsonPlaceholderClient client = createClient();
        return client.getUser(userId);
    }

    public String createPost(String title, String body, int userId) {
        JsonPlaceholderClient client = createClient();
        String requestBody = String.format(
                "{\"title\":\"%s\",\"body\":\"%s\",\"userId\":%d}",
                title, body, userId);
        return client.createPost(requestBody);
    }

    private JsonPlaceholderClient createClient() {
        URI baseUri = getBaseUri();
        return RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .build(JsonPlaceholderClient.class);
    }

    private URI getBaseUri() {
        String stablemockUrl = System.getProperty("stablemock.baseUrl");
        if (stablemockUrl != null && !stablemockUrl.isEmpty()) {
            return URI.create(stablemockUrl);
        }
        return URI.create(defaultThirdPartyUrl);
    }
}

