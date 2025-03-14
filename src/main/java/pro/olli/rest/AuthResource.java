/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.rest;

import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;
import pro.olli.repository.UserRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Path("/auth")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    private final UserRepository userRepository;
    private final SecurityContext securityContext;

    public AuthResource(UserRepository userRepository, SecurityContext securityContext) {
        this.userRepository = userRepository;
        this.securityContext = securityContext;
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
            String accessToken = principal.getClaim("access_token");

            Map<String, Object> userData = new HashMap<>();
            userData.put("userId", userId);
            userData.put("email", email);
            userData.put("businessId", businessId);
            userData.put("createdAt", Instant.now().toString());
            userData.put("token", Map.of("accessToken", accessToken));
            userData.put("syncHistory", new java.util.ArrayList<>());

            userRepository.getBusiness(businessId)
                .flatMap(businessOpt -> {
                    if (businessOpt.isEmpty()) {
                        // New business - admin registers
                        Map<String, Object> businessData = new HashMap<>();
                        businessData.put("businessId", businessId);
                        businessData.put("name", businessId); // Placeholder
                        businessData.put("createdAt", Instant.now().toString());
                        return userRepository.saveBusiness(businessId, businessData)
                            .flatMap(v -> userRepository.saveBusinessUser(businessId, userId, userData));
                    } else {
                        // Existing business - add employee
                        return userRepository.saveBusinessUser(businessId, userId, userData);
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
