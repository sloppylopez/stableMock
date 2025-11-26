package example;

import com.stablemock.U;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@U(urls = { "https://jsonplaceholder.typicode.com" })
public class QuarkusIntegrationTest {

    @Test
    public void testGetUser(int port) {
        RestAssured.given()
                .when()
                .get("/api/users/1")
                .then()
                .statusCode(200)
                .body(containsString("id"))
                .body(containsString("name"));
    }

    @Test
    public void testCreatePost(int port) {
        RestAssured.given()
                .queryParam("title", "Test Title")
                .queryParam("body", "Test Body")
                .queryParam("userId", 1)
                .when()
                .post("/api/posts")
                .then()
                .statusCode(200)
                .body(containsString("id"))
                .body(containsString("Test Title"));
    }
}

