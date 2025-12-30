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
    public ResponseEntity<String> executeGraphQL(@RequestBody String graphqlBody) {
        String response = thirdPartyService.executeGraphQLQuery(graphqlBody);
        return ResponseEntity.ok(response);
    }
}

