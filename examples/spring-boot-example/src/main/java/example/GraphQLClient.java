package example;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "graphQLClient", url = "${app.graphql.url}")
public interface GraphQLClient {
    
    @PostMapping(value = "/graphql", consumes = "application/json")
    String executeQuery(@RequestBody String requestBody);
}
