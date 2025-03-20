/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service;

import com.google.gson.Gson;
import io.opentelemetry.api.trace.Span;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.core.logging.TracingService;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.SyncTask;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.Factory;
import pro.olli.repository.SyncRepository;
import pro.olli.connector.ConnectorFactory;
import pro.olli.connector.Connector;

@Startup
@ApplicationScoped
public class SyncService {
    private static final Logger LOG = Logger.getLogger(SyncService.class);

    private final TaskQueueService taskQueueService;
    private final ConnectorFactory connectorFactory;
    private final SyncRepository syncRepository;
    private final Gson gson;
    private final String projectId;
    private final String location;
    private final TracingService tracingService;

    public SyncService(
        TaskQueueService taskQueueService,
        ConnectorFactory connectorFactory,
        @Factory SyncRepository syncRepository,
        Gson gson,
        TracingService tracingService,
        @ConfigProperty(name = "quarkus.google.cloud.project-id", defaultValue = "olli-ai") String projectId,
        @ConfigProperty(name = "quarkus.google.cloud.location", defaultValue = "us-west1") String location) {
        this.taskQueueService = taskQueueService;
        this.connectorFactory = connectorFactory;
        this.syncRepository = syncRepository;
        this.gson = gson;
        this.tracingService = tracingService;
        this.projectId = projectId;
        this.location = location;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public Uni<String> sync(String businessId, String userId, String source, String directory) {
        // Create a new client span for the sync operation
        return tracingService.withClientSpan("sync", () -> {
            Span span = Span.current();
            span.setAttribute("business.id", businessId);
            span.setAttribute("user.id", userId);
            span.setAttribute("sync.source", source);
            span.setAttribute("sync.directory", directory);

            return doSync(businessId, userId, source, directory);
        }, Map.of("operation", "sync_folder"));
    }

    /**
     * Internal method for sync operation, to be called after trace context setup
     */
    private Uni<String> doSync(String businessId, String userId, String source, String directory) {
        return Uni.createFrom().emitter(emitter -> {
            Span span = Span.current();

            // Record the sync attempt
            tracingService.addEvent("sync_started", Map.of(
                "businessId", businessId,
                "userId", userId,
                "source", source,
                "directory", directory
            ));

            Connector provider = connectorFactory.getProvider(source);
            if (provider == null) {
                String errorMsg = "Unsupported source: " + source;
                LOG.errorf("Unsupported source: %s for client %s, user %s", source, businessId, userId);

                // Record the error in the span
                span.setAttribute("error", true);
                span.setAttribute("error.type", "unsupported_source");
                span.setAttribute("error.message", errorMsg);

                emitter.complete(errorMsg);
                return;
            }

            String taskId = UUID.randomUUID().toString();
            span.setAttribute("sync.task_id", taskId);

            SyncTask taskData = new SyncTask(businessId, userId, source, directory);
            String jsonPayload = gson.toJson(taskData);

            // Record enqueue event
            tracingService.addEvent("task_enqueued", Map.of("taskId", taskId));

            taskQueueService.enqueueTask(taskId, jsonPayload)
                .onItem().invoke(v -> {
                    // Record successful queue operation
                    tracingService.addEvent("task_enqueue_success", Map.of("taskId", taskId));

                    SyncHistoryEntry historyEntry = new SyncHistoryEntry(taskId, Instant.now(), source, directory, 0, "queued", null);

                    // Add trace context to the async operation
                    syncRepository.appendSyncHistory(businessId, userId, historyEntry)
                        .invoke(() -> {
                            LOG.infof("Updated sync history for client %s, user %s", businessId, userId);
                            tracingService.addEvent("sync_history_updated", null);
                        })
                        .onFailure().invoke(e -> {
                            LOG.errorf("Failed to update sync history for client %s, user %s: %s", businessId, userId, e.getMessage());
                            tracingService.addEvent("sync_history_update_failed", Map.of("error", e.getMessage()));
                        })
                        .subscribeAsCompletionStage();
                })
                .subscribe().with(
                    v -> emitter.complete(taskId),
                    e -> {
                        LOG.errorf("Failed to enqueue sync task for client %s, user %s: %s", businessId, userId, e.getMessage());
                        emitter.complete("Failed to queue task: " + e.getMessage());
                    }
                );
        });
    }
}
