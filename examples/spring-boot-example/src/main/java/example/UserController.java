package example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {
    
    private final ThirdPartyService thirdPartyService;
    
    @Autowired
    public UserController(ThirdPartyService thirdPartyService) {
        this.thirdPartyService = thirdPartyService;
    }
    
    @GetMapping("/users/{id}")
    public ResponseEntity<String> getUser(@PathVariable int id) {
        String user = thirdPartyService.getUser(id);
        return ResponseEntity.ok(user);
    }
    
    @PostMapping("/posts")
    public ResponseEntity<String> createPost(
            @RequestParam String title,
            @RequestParam String body,
            @RequestParam int userId) {
        String post = thirdPartyService.createPost(title, body, userId);
        return ResponseEntity.ok(post);
    }
    
    @GetMapping("/postmanecho/users/{id}")
    public ResponseEntity<String> getUserFromPostmanEcho(@PathVariable int id) {
        String user = thirdPartyService.getUserFromPostmanEcho(id);
        return ResponseEntity.ok(user);
    }

    @PostMapping(value = "/postmanecho/xml", consumes = "application/xml")
    public ResponseEntity<String> postXmlToPostmanEcho(@RequestBody String xmlBody) {
        String response = thirdPartyService.postXmlToPostmanEcho(xmlBody);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/graphql", consumes = "application/json")
    public ResponseEntity<String> executeGraphQL(@RequestBody String requestBody) {
        String response = thirdPartyService.executeGraphQL(requestBody);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/posts")
    public ResponseEntity<String> getPostsWithQueryParams(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String correlationId) {
        String posts = thirdPartyService.getPostsWithQueryParams(page, limit, timestamp, correlationId);
        return ResponseEntity.ok(posts);
    }
    
    @PostMapping(value = "/posts/with-dynamic-fields", consumes = "application/json")
    public ResponseEntity<String> createPostWithDynamicFields(@RequestBody String body) {
        String post = thirdPartyService.createPostWithDynamicFields(body);
        return ResponseEntity.ok(post);
    }
}

