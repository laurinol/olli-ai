/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.service;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

/**
 * Service for synchronizing files across storage providers. Uses StorageFactory to delegate provider-specific
 * operations.
 */
@Startup
public class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class);

    private final Firestore firestore;
    private final StorageFactory storageFactory;
    private final CloudTasksClient cloudTasksClient;
    private final Gson gson;

    @ConfigProperty(name = "quarkus.google.cloud.project-id", defaultValue = "olli-ai")
    String projectId;
    @ConfigProperty(name = "quarkus.google.cloud.location", defaultValue = "us-west1")
    String location;

    public SyncService(Firestore firestore, StorageFactory storageFactory, CloudTasksClient cloudTasksClient) {
        this.firestore = firestore;
        this.storageFactory = storageFactory;
        this.cloudTasksClient = cloudTasksClient;
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

            enqueueSyncTask(taskId, taskData)
                .onItem().invoke(v -> {
                    // Record in Firestore history
                    Map<String, Object> historyEntry = new HashMap<>();
                    historyEntry.put("taskId", taskId);
                    historyEntry.put("source", source);
                    historyEntry.put("folderId", directory);
                    historyEntry.put("timestamp", Instant.now().toString());

                    try {
                        firestore.collection("clients").document(clientId)
                            .collection("users").document(userId)
                            .update("syncHistory", com.google.cloud.firestore.FieldValue.arrayUnion(historyEntry))
                            .get(); // Blocking - improve later
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

    private Uni<Void> enqueueSyncTask(String taskId, Map<String, String> taskData) {
        return Uni.createFrom().emitter(emitter -> {
            String queue = "sync-queue";       // Configure in GCP

            QueueName queueName = QueueName.of(projectId, location, queue);
            String jsonBody = gson.toJson(taskData);
            Task task = Task.newBuilder()
                .setHttpRequest(HttpRequest.newBuilder()
                    .setUrl("http://localhost:8080/process") // Adjust for prod
                    .setHttpMethod(HttpMethod.POST)
                    .setBody(ByteString.copyFrom(jsonBody.getBytes(StandardCharsets.UTF_8)))
                    .build())
                .setName(queueName.toString() + "/tasks/" + taskId)
                .build();

            try {
                cloudTasksClient.createTask(queueName, task);
                LOG.infof("Queued sync task %s for client %s, user %s, source %s",
                    taskId, taskData.get("clientId"), taskData.get("userId"), taskData.get("source"));
                emitter.complete(null);
            } catch (Exception e) {
                LOG.errorf("Failed to enqueue sync task %s: %s", taskId, e.getMessage());
                emitter.fail(e);
            }
        });
    }
}
