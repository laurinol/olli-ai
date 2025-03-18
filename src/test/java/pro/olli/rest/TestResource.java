/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.rest;

import com.google.gson.Gson;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import pro.olli.models.Invite;
import pro.olli.models.SyncTask;
import pro.olli.queue.RabbitMqTaskService;
import pro.olli.repository.UserRepository;
import pro.olli.worker.SyncWorker;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This resource exposes endpoints used only for testing purposes
 */
@Path("/test")
@ApplicationScoped
public class TestResource {

    private static final Logger LOG = Logger.getLogger(TestResource.class);

    @Inject
    UserRepository userRepository;

    @Inject
    RabbitMqTaskService rabbitMqTaskService;

    @Inject
    SyncWorker syncWorker;

    @Inject
    Gson gson;

    @GET
    @Path("/get-latest-invite")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> getLatestInvite(@QueryParam("email") String email) {
        return userRepository.getInviteByEmail(email)
                .map(inviteOpt -> inviteOpt.map(Invite::getToken).orElse("no-token-found"));
    }

    @GET
    @Path("/check-queue-task")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> checkQueueTask(@QueryParam("taskId") String taskId) {
        return rabbitMqTaskService.peekTask(taskId)
                .map(taskOpt -> {
                    if (taskOpt.isEmpty()) {
                        return Response.ok(Map.of("exists", false)).build();
                    }

                    SyncTask task = gson.fromJson(taskOpt.get().getJsonPayload(), SyncTask.class);
                    Map<String, Object> result = new HashMap<>();
                    result.put("exists", true);
                    result.put("businessId", task.getBusinessId());
                    result.put("userId", task.getUserId());
                    result.put("source", task.getSource());
                    return Response.ok(result).build();
                })
                .onFailure().recoverWithItem(e -> {
                    LOG.error("Failed to check queue task", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", e.getMessage()))
                            .build();
                });
    }

    @POST
    @Path("/process-sync-task")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> processSyncTask(@QueryParam("taskId") String taskId) {
        return rabbitMqTaskService.dequeueSpecificTask(taskId)
                .flatMap(task -> syncWorker.processTask(task.getTaskId(), task.getJsonPayload())
                        .map(v -> Response.ok(Map.of("processed", true)).build()))
                .onFailure().recoverWithItem(e -> {
                    LOG.error("Failed to process sync task", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", e.getMessage(), "processed", false))
                            .build();
                });
    }

    @GET
    @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String simpleHealthCheck() {
        return "OK";
    }
}
