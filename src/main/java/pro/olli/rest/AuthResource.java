/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.rest;

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
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.User;
import pro.olli.repository.Factory;
import pro.olli.repository.UserRepository;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Path("/auth")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    private final UserRepository userRepository;
    private final SecurityContext securityContext;

    @Inject
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
    @Path("/profile")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> getProfile(@Context SecurityContext ctx) {
        return Uni.createFrom().emitter(emitter -> {
            Principal principal = ctx.getUserPrincipal();
            if (principal == null) {
                // Support test mode where no auth is required
                if ("dev".equals(System.getProperty("quarkus.profile", "prod"))) {
                    String testEmail = System.getProperty("test.email", "test@example.com");
                    String testBusinessId = System.getProperty("test.businessId", "test-business");
                    String testUserId = System.getProperty("test.userId", "user-test123");

                    Map<String, Object> userData = new HashMap<>();
                    userData.put("userId", testUserId);
                    userData.put("email", testEmail);
                    userData.put("businessId", testBusinessId);
                    userData.put("role", "admin");
                    userData.put("dev", true);

                    LOG.infof("Returning test profile data in dev mode: %s", userData);
                    emitter.complete(Response.ok(userData).build());
                    return;
                }

                LOG.error("No authenticated user in profile request");
                emitter.complete(Response.status(Response.Status.UNAUTHORIZED).build());
                return;
            }

            String userId = principal.getName();
            LOG.infof("Getting profile for authenticated user: %s", userId);

            userRepository.getUser(userId)
                .flatMap(userOpt -> {
                    if (userOpt.isEmpty()) {
                        LOG.warnf("Profile request failed - user not found: %s", userId);
                        return Uni.createFrom().item(
                            Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "User not found"))
                                .build()
                        );
                    }

                    User user = userOpt.get();
                    LOG.debugf("Found user for profile: id=%s, email=%s", user.getId(), user.getEmail());

                    // Ensure metadata exists and is not null
                    Map<String, Object> userData = new HashMap<>();
                    userData.put("userId", user.getId());
                    userData.put("email", user.getEmail());

                    if (user.getMetadata() != null) {
                        userData.putAll(user.getMetadata());
                    }

                    // Get the business ID from metadata
                    String businessId = (String) userData.get("businessId");

                    if (businessId == null) {
                        LOG.warnf("User %s has no business ID in metadata", user.getId());
                        return Uni.createFrom().item(Response.ok(userData).build());
                    }

                    // Get the business user record to append role info
                    return userRepository.getBusinessUser(businessId, user.getId())
                        .map(businessUserOpt -> {
                            if (businessUserOpt.isPresent()) {
                                BusinessUser businessUser = businessUserOpt.get();
                                userData.put("role", businessUser.getRole());
                                userData.put("businessName", businessUser.getBusinessId()); // TODO: Get actual business name

                                if (businessUser.getSyncHistory() != null) {
                                    userData.put("syncHistory", businessUser.getSyncHistory());
                                }

                                LOG.debugf("Added business user data: role=%s", businessUser.getRole());
                            } else {
                                LOG.warnf("No business user record found for user %s in business %s", user.getId(), businessId);
                            }

                            return Response.ok(userData).build();
                        });
                })
                .subscribe().with(
                    emitter::complete,
                    error -> {
                        LOG.errorf("Error retrieving user profile: %s", error.getMessage());
                        emitter.complete(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", "Failed to retrieve profile"))
                            .build());
                    }
                );
        });
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
                        Business business = new Business(businessId, businessId, "basic"); // Default to basic plan

                        // Create business user
                        BusinessUser businessUser = new BusinessUser(userId, businessId, email,
                            principal.getClaim("name"), "admin"); // Owner gets admin role

                        return userRepository.saveBusiness(business)
                            .flatMap(v -> userRepository.saveBusinessUser(businessUser));
                    } else {
                        // Existing business - add employee
                        BusinessUser businessUser = new BusinessUser(userId, businessId, email,
                            principal.getClaim("name"), "user"); // New users get standard role

                        return userRepository.saveBusinessUser(businessUser);
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
