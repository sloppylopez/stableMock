package example;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient
public interface JsonPlaceholderClient {

    @GET
    @Path("/users/{id}")
    String getUser(@PathParam("id") int id);

    @POST
    @Path("/posts")
    String createPost(String body);
}

