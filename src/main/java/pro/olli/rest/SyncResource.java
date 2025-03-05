package pro.olli.rest;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
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
import org.jboss.logging.Logger;
import pro.olli.service.SyncService;

@Path("/auth")
public class SyncResource {

    private static final Logger LOG = Logger.getLogger(SyncResource.class);

    private final SyncService syncService;

    public SyncResource(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * REST endpoint for syncing a folder.
     * @param source
     * @param folderId
     * @param ctx
     * @return An HTTP response indicating success or failure.
     */
    @GET
    @Path("/sync/{source}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> syncFolder(@PathParam("source") String source,
        @QueryParam("folderId") String folderId,
        @Context SecurityContext ctx) {
        LOG.debugf("Sync requested for source %s, folderId %s", source, folderId);
        if (ctx.getUserPrincipal() == null) {
            LOG.warnf("Unauthenticated request to /auth/sync/%s", source);
            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Authentication required"))
                .build());
        }
        String clientId = ctx.getUserPrincipal().getName();
        LOG.infof("Sync requested for client %s, source %s, folderId %s", clientId, source, folderId);
        return syncService.sync(clientId, source, folderId)
            .map(taskId -> {
                if (taskId == null) {
                    LOG.warnf("No task ID returned for client %s, source %s", clientId, source);
                    return Response.status(Response.Status.NO_CONTENT)
                        .entity(Map.of("message", "No sync task created"))
                        .build();
                }
                return Response.ok(Map.of("taskId", taskId, "message", "Sync queued")).build();
            })
            .onFailure().recoverWithItem(throwable -> {
                LOG.errorf("Sync failed for client %s, source %s: %s", clientId, source, throwable.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Sync failed: " + throwable.getMessage()))
                    .build();
            });
    }

    /**
     * REST endpoint for doing a health check
     * @return "OK"
     */
    @GET
    @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String healthCheck() {
        return "OK";
    }
}
