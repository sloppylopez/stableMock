package example;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
public class UserResource {

    @Inject
    ThirdPartyService thirdPartyService;

    @GET
    @Path("/users/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUser(@PathParam("id") int id) {
        String user = thirdPartyService.getUser(id);
        return Response.ok(user).build();
    }

    @POST
    @Path("/posts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response createPost(
            @QueryParam("title") String title,
            @QueryParam("body") String body,
            @QueryParam("userId") int userId) {
        String post = thirdPartyService.createPost(title, body, userId);
        return Response.ok(post).build();
    }
}

