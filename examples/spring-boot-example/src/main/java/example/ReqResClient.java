package example;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;

@FeignClient(name = "reqResClient", url = "${app.reqres.url:https://reqres.in}")
public interface ReqResClient {
    
    @GetMapping("/api/users/{id}")
    String getUser(@PathVariable int id);
}

