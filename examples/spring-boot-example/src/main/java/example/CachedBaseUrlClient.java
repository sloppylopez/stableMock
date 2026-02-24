package example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Simulates the "Spring caches base URL at context startup" failure mode.
 * The URL is resolved once when this bean is created (context init), not at request time.
 * With Option A (per-invocation WireMock server), all requests will go to the same
 * (class-level) port because this client never sees the per-invocation port.
 */
@Component
public class CachedBaseUrlClient {

    private final String baseUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public CachedBaseUrlClient(@Value("${app.postmanecho.url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String get(int id) {
        String url = baseUrl + "/get?id=" + id;
        return restTemplate.getForObject(url, String.class);
    }
}
