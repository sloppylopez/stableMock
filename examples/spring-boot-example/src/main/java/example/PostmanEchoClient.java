package example;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "postmanEchoClient", url = "${app.postmanecho.url}")
public interface PostmanEchoClient {
    
    @GetMapping("/get")
    String getUser(@RequestParam("id") int id);

    @PostMapping(value = "/post", consumes = "application/xml")
    String postXml(@RequestBody String xmlBody);
}

