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
}

