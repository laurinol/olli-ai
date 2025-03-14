/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.worker;

import com.google.cloud.aiplatform.v1beta1.IndexDatapoint;
import com.google.cloud.aiplatform.v1beta1.IndexDatapoint.Restriction; // Fix
import com.google.cloud.aiplatform.v1beta1.IndexName;
import com.google.cloud.aiplatform.v1beta1.IndexServiceClient;
import com.google.cloud.aiplatform.v1beta1.UpsertDatapointsRequest;
import com.google.gson.Gson;
import io.quarkus.runtime.Startup;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.annotations.Channel;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.ai.EmbeddingService;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SyncWorker {

    private static final Logger LOG = Logger.getLogger(SyncWorker.class);

    @ConfigProperty(name = "gcp.project-id", defaultValue = "olli-ai")
    String projectId;

    @ConfigProperty(name = "gcp.vertex.location", defaultValue = "us-west1")
    String location;

    private final StorageFactory storageFactory;
    private final EmbeddingService embeddingService;
    private final IndexServiceClient indexServiceClient;
    private final Gson gson;

    @Inject
    public SyncWorker(StorageFactory storageFactory, EmbeddingService embeddingService, IndexServiceClient indexServiceClient) {
        this.storageFactory = storageFactory;
        this.embeddingService = embeddingService;
        this.indexServiceClient = indexServiceClient;
        this.gson = new Gson();
    }

    @Incoming("sync-tasks")
    @Blocking(ordered = false)
    public Uni<Void> processTask(String taskId, String jsonPayload) {
        return Uni.createFrom().emitter(emitter -> {
            Map<String, String> taskData = gson.fromJson(jsonPayload, Map.class);
            String clientId = taskData.get("clientId");
            String userId = taskData.get("userId");
            String source = taskData.get("source");
            String folderId = taskData.get("folderId");

            StorageProvider provider = storageFactory.getProvider(source);
            if (provider == null) {
                LOG.errorf("Unsupported source %s for task %s", source, taskId);
                emitter.fail(new IllegalArgumentException("Unsupported source: " + source));
                return;
            }

            provider.listFiles(folderId)
                .flatMap(files -> Uni.createFrom().item(files)
                    .map(fileList -> fileList.stream()
                        .map(file -> processFile(provider, file, taskId, clientId, userId))
                        .toList())
                    .flatMap(unis -> Uni.combine().all().unis(unis).combinedWith(results -> null)))
                .subscribe().with(
                    results -> emitter.complete(null),
                    emitter::fail
                );
        });
    }

    private Uni<Void> processFile(StorageProvider provider, String file, String taskId, String clientId, String userId) {
        return provider.downloadFile(file)
            .flatMap(this::extractText)
            .flatMap(text -> embeddingService.generateEmbedding(text))
            .flatMap(embedding -> storeInVectorSearch(embedding, file, taskId, clientId, userId));
    }

    private Uni<String> extractText(InputStream inputStream) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                String text = new String(inputStream.readAllBytes());
                emitter.complete(text);
            } catch (Exception e) {
                emitter.fail(e);
            } finally {
                try {
                    inputStream.close();
                } catch (Exception ignored) {}
            }
        });
    }

    private Uni<Void> storeInVectorSearch(List<Float> embedding, String file, String taskId, String clientId, String userId) {
        return Uni.createFrom().emitter(emitter -> {
            IndexName indexName = IndexName.of(projectId, location, "sync-index");
            IndexDatapoint datapoint = IndexDatapoint.newBuilder()
                .setDatapointId(taskId + "_" + file)
                .addAllFeatureVector(embedding)
                .addRestricts(Restriction.newBuilder().setNamespace("clientId").setAllowList(0, clientId).build()) // Fix
                .addRestricts(Restriction.newBuilder().setNamespace("userId").setAllowList(1, userId).build())   // Fix
                .addRestricts(Restriction.newBuilder().setNamespace("file").setAllowList(2, file).build())       // Fix
                .build();

            UpsertDatapointsRequest request = UpsertDatapointsRequest.newBuilder()
                .setIndex(indexName.toString())
                .addDatapoints(datapoint)
                .build();

            try {
                indexServiceClient.upsertDatapoints(request);
                LOG.infof("Stored embedding for file %s in Vector Search for task %s", file, taskId);
                emitter.complete(null);
            } catch (Exception e) {
                LOG.errorf("Failed to store embedding for file %s in Vector Search: %s", file, e.getMessage());
                emitter.fail(e);
            }
        });
    }
}
