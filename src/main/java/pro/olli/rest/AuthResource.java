/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.rest;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Path("/auth")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    private final Firestore firestore;

    @Inject
    public AuthResource(Firestore firestore) {
        this.firestore = firestore;
    }

    @GET
    @Path("/google/login")
    public Response googleLogin(@QueryParam("businessId") String businessId) {
        // Redirect to Google OAuth - handled by Quarkus OIDC
        return Response.status(Response.Status.TEMPORARY_REDIRECT)
            .header("Location", "http://localhost:8080/auth/google/callback?businessId=" + businessId)
            .build();
    }

    @GET
    @Path("/google/callback")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> googleCallback(@QueryParam("businessId") String businessId,
        @Context SecurityContext ctx) {
        return Uni.createFrom().emitter(emitter -> {
            OidcJwtCallerPrincipal principal = (OidcJwtCallerPrincipal) ctx.getUserPrincipal();
            if (principal == null) {
                LOG.error("No authenticated user in callback");
                emitter.complete(Response.status(Response.Status.UNAUTHORIZED).build());
                return;
            }

            String userId = principal.getSubject(); // Google sub
            String email = principal.getClaim("email");
            String accessToken = principal.getClaim("access_token"); // From OIDC or Playground

            // Check if business exists
            Uni.createFrom().future(firestore.collection("clients").document(businessId).get())
                .onItem().transformToUni(doc -> {
                    Map<String, Object> userData = new HashMap<>();
                    userData.put("userId", userId);
                    userData.put("email", email);
                    userData.put("businessId", businessId);
                    userData.put("createdAt", Instant.now().toString());
                    userData.put("token", Map.of("accessToken", accessToken));
                    userData.put("syncHistory", new java.util.ArrayList<>());

                    if (!doc.exists()) {
                        // New business - admin registers
                        Map<String, Object> businessData = new HashMap<>();
                        businessData.put("businessId", businessId);
                        businessData.put("name", businessId); // Placeholder - enhance later
                        businessData.put("createdAt", Instant.now().toString());
                        return Uni.createFrom().future(firestore.collection("clients").document(businessId).set(businessData))
                            .flatMap(v -> Uni.createFrom().future(
                                firestore.collection("clients").document(businessId)
                                    .collection("users").document(userId).set(userData)));
                    } else {
                        // Existing business - add employee
                        return Uni.createFrom().future(firestore.collection("clients").document(businessId)
                            .collection("users").document(userId).set(userData));
                    }
                })
                .subscribe().with(
                    v -> {
                        LOG.infof("Registered user %s for business %s", userId, businessId);
                        emitter.complete(Response.ok(Map.of("message", "User registered", "userId", userId)).build());
                    },
                    e -> {
                        LOG.errorf("Registration failed: %s", e.getMessage());
                        emitter.complete(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", e.getMessage())).build());
                    }
                );
        });
    }
}
