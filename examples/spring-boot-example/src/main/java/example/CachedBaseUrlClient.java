package example;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Resolves the base URL at request time so parameterized Option A playback can use
 * each invocation's WireMock server port.
 */
@Component
public class CachedBaseUrlClient {

    private static final String BASE_URL_PROPERTY = "app.postmanecho.url";

    private final Environment environment;
    private final RestTemplate restTemplate = new RestTemplate();

    public CachedBaseUrlClient(Environment environment) {
        this.environment = environment;
    }

    public String get(int id) {
        String baseUrl = environment.getProperty(BASE_URL_PROPERTY);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Missing required property: " + BASE_URL_PROPERTY);
        }
        String url = baseUrl + "/get?id=" + id;
        return restTemplate.getForObject(url, String.class);
    }
}
