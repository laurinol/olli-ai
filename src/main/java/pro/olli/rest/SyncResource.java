/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.rest;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.service.SyncService;

@Path("/auth")
public class SyncResource {

    private static final Logger LOG = Logger.getLogger(SyncResource.class);

    private final SyncService syncService;

    @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "true")
    boolean oidcEnabled;

    public SyncResource(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * REST endpoint for synchronizing files across storage providers
     *
     * @param source     Source storage provider
     * @param folderId   Folder ID to sync
     * @param businessId Business ID
     * @param ctx        Security context
     * @return Response with task ID
     */
    @GET
    @Path("/sync/{source}")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> syncFolder(@PathParam("source") String source,
        @QueryParam("folderId") String folderId,
        @QueryParam("businessId") String businessId,
        @Context SecurityContext ctx) {
        LOG.infof("OIDC enabled: %b, Profile: %s, Source: %s", oidcEnabled,
            System.getProperty("quarkus.profile", "prod"), source);
        String userId;
        String clientId;
        boolean isDev = "dev".equals(System.getProperty("quarkus.profile", "prod"));
        boolean requiresAuth = oidcEnabled && !isDev && !source.equalsIgnoreCase("local");

        LOG.infof("Requires auth: %b, Principal: %s", requiresAuth, ctx.getUserPrincipal());
        if (requiresAuth && ctx.getUserPrincipal() == null) {
            LOG.warnf("Authentication required for /auth/sync/%s", source);
            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Authentication required"))
                .build());
        }

        if (businessId == null || businessId.isEmpty()) {
            LOG.error("Business ID is required");
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Business ID is required"))
                .build());
        }
        clientId = businessId; // Use query param
        userId = ctx.getUserPrincipal() != null ? ctx.getUserPrincipal().getName() :
            System.getenv("TEST_USER_ID") != null ? System.getenv("TEST_USER_ID") : UUID.randomUUID().toString();

        if (ctx.getUserPrincipal() == null) {
            LOG.warnf("No authentication provided for /auth/sync/%s, using fallback userId %s", source, userId);
        } else {
            LOG.infof("Authenticated userId: %s", userId);
        }

        LOG.infof("Sync requested for client %s, user %s, source %s, folderId %s", clientId, userId, source, folderId);
        return syncService.sync(clientId, userId, source, folderId)
            .map(taskId -> {
                if (taskId == null || taskId.startsWith("Failed")) {
                    LOG.warnf("No task ID returned for client %s, user %s, source %s", clientId, userId, source);
                    return Response.status(Response.Status.NO_CONTENT)
                        .entity(Map.of("message", "No sync task created"))
                        .build();
                }
                return Response.ok(Map.of("taskId", taskId, "message", "Sync queued")).build();
            })
            .onFailure().recoverWithItem(throwable -> {
                LOG.errorf("Sync failed for client %s, user %s, source %s: %s", clientId, userId, source,
                    throwable.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Sync failed: " + throwable.getMessage()))
                    .build();
            });
    }


    /**
     * REST endpoint for doing a health check
     *
     * @return "OK"
     */
    @GET
    @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String healthCheck() {
        return "OK";
    }
}
