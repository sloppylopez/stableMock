package example;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;

@FeignClient(name = "reqResClient", url = "${app.reqres.url:https://postman-echo.com}")
public interface ReqResClient {
    
    @GetMapping("/get?id={id}")
    String getUser(@PathVariable("id") int id);
}

