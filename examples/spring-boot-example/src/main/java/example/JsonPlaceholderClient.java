package example;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.net.URI;

@FeignClient(name = "jsonPlaceholderClient", url = "${app.thirdparty.url}")
public interface JsonPlaceholderClient {
    
    @GetMapping("/users/{id}")
    String getUser(@PathVariable int id);
    
    @PostMapping("/posts")
    String createPost(@RequestBody String body);
}

