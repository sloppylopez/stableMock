package example;

import com.stablemock.U;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@U(urls = { "https://jsonplaceholder.typicode.com" })
public class QuarkusDynamicPropertyTest {

    @Test
    public void testGetUserWithDynamicProperty(int port) {
        given()
                .when()
                .get("/api/users/1")
                .then()
                .statusCode(200)
                .body(containsString("id"));
    }

    @Test
    public void testCreatePostWithDynamicProperty(int port) {
        given()
                .queryParam("title", "My Post")
                .queryParam("body", "Post Content")
                .queryParam("userId", 1)
                .when()
                .post("/api/posts")
                .then()
                .statusCode(200)
                .body(containsString("My Post"));
    }
}

