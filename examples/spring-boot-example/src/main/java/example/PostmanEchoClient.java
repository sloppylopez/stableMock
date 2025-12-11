package example;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "postmanEchoClient", url = "${app.postmanecho.url}")
public interface PostmanEchoClient {
    
    @GetMapping("/get?id={id}")
    String getUser(@PathVariable("id") int id);

    @PostMapping(value = "/post", consumes = "application/xml")
    String postXml(@RequestBody String xmlBody);
}

