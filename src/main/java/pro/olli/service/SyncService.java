/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.service;

import com.google.cloud.firestore.Firestore;
import com.google.gson.Gson;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.queue.TaskQueueService;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

/**
 * Service for synchronizing files across storage providers. Uses StorageFactory to delegate provider-specific
 * operations.
 */
@Startup
public class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class);

    private final TaskQueueService taskQueueService;
    private final Firestore firestore;
    private final StorageFactory storageFactory;
    private final Gson gson;

    @ConfigProperty(name = "quarkus.google.cloud.project-id", defaultValue = "olli-ai")
    String projectId;
    @ConfigProperty(name = "quarkus.google.cloud.location", defaultValue = "us-west1")
    String location;

    @Inject
    public SyncService(TaskQueueService taskQueueService, StorageFactory storageFactory, Firestore firestore) {
        this.taskQueueService = taskQueueService;
        this.storageFactory = storageFactory;
        this.firestore = firestore;
        this.gson = new Gson();
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

                    try {
                        firestore.collection("clients").document(clientId)
                            .collection("users").document(userId)
                            .update("syncHistory", com.google.cloud.firestore.FieldValue.arrayUnion(historyEntry))
                            .get();
                    } catch (Exception e) {
                        LOG.errorf("Failed to update sync history for client %s, user %s: %s", clientId, userId, e.getMessage());
                    }
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
