package example;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;


@FeignClient(name = "postmanEchoClient", url = "${app.reqres.url}")
public interface PostmanEchoClient {
    
    @GetMapping("/get?id={id}")
    String getUser(@PathVariable("id") int id);
}

