/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service;

import com.google.gson.Gson;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.SyncRepository;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Startup
@ApplicationScoped
public class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class);

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

    public Uni<String> sync(String clientId, String userId, String source, String directory) {
        return Uni.createFrom().emitter(emitter -> {
            StorageProvider provider = storageFactory.getProvider(source);
            if (provider == null) {
                LOG.errorf("Unsupported source: %s for client %s, user %s", source, clientId, userId);
                emitter.complete("Unsupported source: " + source);
                return;
            }

            String taskId = UUID.randomUUID().toString();
            Map<String, String> taskData = new HashMap<>();
            taskData.put("clientId", clientId);
            taskData.put("userId", userId);
            taskData.put("source", source);
            taskData.put("folderId", directory);

            String jsonPayload = gson.toJson(taskData);
            enqueueSyncTask(taskId, jsonPayload)
                .onItem().invoke(v -> {
                    Map<String, Object> historyEntry = new HashMap<>();
                    historyEntry.put("taskId", taskId);
                    historyEntry.put("source", source);
                    historyEntry.put("folderId", directory);
                    historyEntry.put("timestamp", Instant.now().toString());

                    syncRepository.appendSyncHistory(clientId, userId, historyEntry)
                        .subscribe().with(
                            ignored -> LOG.infof("Updated sync history for client %s, user %s", clientId, userId),
                            e -> LOG.errorf("Failed to update sync history for client %s, user %s: %s", clientId, userId, e.getMessage())
                        );
                })
                .subscribe().with(
                    v -> emitter.complete(taskId),
                    e -> {
                        LOG.errorf("Failed to enqueue sync task for client %s, user %s: %s", clientId, userId, e.getMessage());
                        emitter.complete("Failed to queue task: " + e.getMessage());
                    }
                );
        });
    }

    private Uni<Void> enqueueSyncTask(String taskId, String jsonPayload) {
        return taskQueueService.enqueueTask(taskId, jsonPayload);
    }
}
