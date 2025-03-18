/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service;

import com.google.gson.Gson;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.SyncTask;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.SyncRepository;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import static io.quarkus.arc.ComponentsProvider.LOG;

@Startup
@ApplicationScoped
public class SyncService {
    private final TaskQueueService taskQueueService;
    private final StorageFactory storageFactory;
    private final SyncRepository syncRepository;
    private final Gson gson;
    private final String projectId;
    private final String location;

    public SyncService(
        TaskQueueService taskQueueService,
        StorageFactory storageFactory,
        SyncRepository syncRepository,
        Gson gson,
        @ConfigProperty(name = "quarkus.google.cloud.project-id", defaultValue = "olli-ai") String projectId,
        @ConfigProperty(name = "quarkus.google.cloud.location", defaultValue = "us-west1") String location) {
        this.taskQueueService = taskQueueService;
        this.storageFactory = storageFactory;
        this.syncRepository = syncRepository;
        this.gson = gson;
        this.projectId = projectId;
        this.location = location;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public Uni<String> sync(String businessId, String userId, String source, String directory) {
        return Uni.createFrom().emitter(emitter -> {
            StorageProvider provider = storageFactory.getProvider(source);
            if (provider == null) {
                LOG.errorf("Unsupported source: %s for client %s, user %s", source, businessId, userId);
                emitter.complete("Unsupported source: " + source);
                return;
            }

            String taskId = UUID.randomUUID().toString();
            SyncTask taskData = new SyncTask(businessId, userId, source, directory);
            String jsonPayload = gson.toJson(taskData);
            taskQueueService.enqueueTask(taskId, jsonPayload)
                .onItem().invoke(v -> {
                    SyncHistoryEntry historyEntry = new SyncHistoryEntry(taskId, Instant.now(), source, directory, 0, "queued", null);
                    // Non-blocking fire-and-forget
                    syncRepository.appendSyncHistory(businessId, userId, historyEntry)
                        .invoke(() -> LOG.infof("Updated sync history for client %s, user %s", businessId, userId))
                        .onFailure().invoke(e -> LOG.errorf("Failed to update sync history for client %s, user %s: %s", businessId, userId, e.getMessage()))
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
