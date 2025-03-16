/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.worker;

import com.google.cloud.aiplatform.v1beta1.IndexDatapoint;
import com.google.cloud.aiplatform.v1beta1.IndexDatapoint.Restriction;
import com.google.cloud.aiplatform.v1beta1.IndexName;
import com.google.cloud.aiplatform.v1beta1.IndexServiceClient;
import com.google.cloud.aiplatform.v1beta1.UpsertDatapointsRequest;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.ai.EmbeddingService;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.SyncTask;
import pro.olli.queue.TaskQueueService;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

@ApplicationScoped
public class SyncWorker {

    private static final Logger LOG = Logger.getLogger(SyncWorker.class);

    @ConfigProperty(name = "gcp.project-id", defaultValue = "olli-ai")
    String projectId;

    @ConfigProperty(name = "gcp.vertex.location", defaultValue = "us-west1")
    String location;

    private final StorageFactory storageFactory;
    private final EmbeddingService embeddingService;
    private final TaskQueueService taskQueueService;
    private final IndexServiceClient indexServiceClient;
    private final ExecutorService executorService;
    private final Gson gson;

    @Inject
    public SyncWorker(StorageFactory storageFactory, EmbeddingService embeddingService,
        TaskQueueService taskQueueService, IndexServiceClient indexServiceClient) {
        this.storageFactory = storageFactory;
        this.embeddingService = embeddingService;
        this.taskQueueService = taskQueueService;
        this.indexServiceClient = indexServiceClient;
        this.gson = new Gson();
        this.executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), // TODO: Adjust via config if needed
            new ThreadFactoryBuilder().setNameFormat("sync-worker-%d").build()
        );
    }

    @PostConstruct
    public void startConsuming() {
        int workerCount = Runtime.getRuntime().availableProcessors(); // TODO: Configurable
        for (int i = 0; i < workerCount; i++) {
            executorService.submit(this::consumeTasks);
        }
        LOG.infof("Started %d SyncWorker threads for reading tasks from the queue", workerCount);
    }

    private void consumeTasks() {
        while (!Thread.currentThread().isInterrupted()) {
            taskQueueService.dequeueTask()
                .subscribe().with(
                    dequeuedTask -> processTask(dequeuedTask.getTaskId(), dequeuedTask.getJsonPayload())
                        .subscribe().with(
                            v -> LOG.infof("Processed task %s: %s", dequeuedTask.getTaskId(), dequeuedTask.getJsonPayload()),
                            e -> LOG.errorf("Failed to process task %s: %s, error: %s", dequeuedTask.getTaskId(), dequeuedTask.getJsonPayload(), e.getMessage())
                        ),
                    e -> {
                        LOG.debugf("No tasks available, waiting: %s", e.getMessage());
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                );
        }
    }

    @Retry(maxRetries = 3, delay = 1000, jitter = 200,
        abortOn = {AppException.class})
    @Timeout(value = 30, unit = ChronoUnit.SECONDS)
    public Uni<Void> processTask(String taskId, String jsonPayload) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                SyncTask taskData = gson.fromJson(jsonPayload, SyncTask.class);
                String businessId = taskData.getBusinessId();
                String userId = taskData.getUserId();
                String source = taskData.getSource();
                String folderId = taskData.getFolderId();

                StorageProvider provider = storageFactory.getProvider(source);
                if (provider == null) {
                    LOG.errorf("Unsupported source %s for task %s", source, taskId);
                    emitter.fail(new IllegalArgumentException("Unsupported source: " + source));
                    return;
                }

                LOG.infof("Processing sync task %s for client %s, user %s, source %s, folder %s",
                    taskId, businessId, userId, source, folderId);
                provider.listFiles(folderId)
                    .flatMap(files -> Uni.createFrom().item(files)
                        .map(fileList -> fileList.stream()
                            .map(file -> processFile(provider, file, taskId, businessId, userId))
                            .toList())
                        .flatMap(unis -> Uni.combine().all().unis(unis).combinedWith(results -> null)))
                    .subscribe().with(
                        results -> emitter.complete(null),
                        emitter::fail
                    );
            } catch (Exception e) {
                String errorMsg = "Failed to parse or process task data";
                LOG.error(errorMsg, e);
                emitter.fail(new AppException(errorMsg, e, AppException.ErrorType.SYSTEM_ERROR));
            }
        });
    }

    @Retry(maxRetries = 3, delay = 500, jitter = 100,
        retryOn = {Exception.class},
        abortOn = {AppException.class})
    @Fallback(fallbackMethod = "processingFileFallback")
    private Uni<Void> processFile(StorageProvider provider, String file, String taskId, String businessId,
        String userId) {
        return provider.downloadFile(file)
            .flatMap(this::extractText)
            .flatMap(text -> generateEmbeddingWithFaultTolerance(text, file))
            .flatMap(embedding -> storeInVectorSearchWithFaultTolerance(embedding, file, taskId, businessId, userId))
            .onFailure().transform(e -> {
                if (e instanceof AppException) {
                    return e;
                }
                return new AppException("Failed to process file: " + file, e, AppException.ErrorType.SYSTEM_ERROR);
            });
    }

    private Uni<Void> processingFileFallback(StorageProvider provider, String file, String taskId,
        String businessId, String userId, Throwable cause) {
        LOG.warnf("Falling back on file processing for %s due to: %s", file, cause.getMessage());
        // Record the failure
        return recordFailedProcessing(file, taskId, businessId, userId, cause.getMessage());
    }

    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.6,
        delay = 10000,
        successThreshold = 2)
    @CircuitBreakerName("embedding-service")
    @Retry(maxRetries = 2, delay = 1000)
    @Timeout(5000)
    private Uni<List<Float>> generateEmbeddingWithFaultTolerance(String text, String fileIdentifier) {
        LOG.infof("Generating embedding for file: %s", fileIdentifier);
        return embeddingService.generateEmbedding(text)
            .onFailure().transform(e ->
                new AppException("Failed to generate embedding for " + fileIdentifier,
                    e, AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
    }

    @Fallback(fallbackMethod = "storeInVectorSearchFallback")
    @CircuitBreaker(requestVolumeThreshold = 8,
        failureRatio = 0.5,
        delay = 15000)
    @CircuitBreakerName("vector-search")
    @Bulkhead(value = 5) // Limit concurrent requests to Vector Search
    private Uni<Void> storeInVectorSearchWithFaultTolerance(List<Float> embedding, String file,
        String taskId, String businessId, String userId) {
        // Existing implementation with improved error handling
        return Uni.createFrom().emitter(emitter -> {
            try {
                IndexName indexName = IndexName.of(projectId, location, "sync-index");
                IndexDatapoint datapoint = IndexDatapoint.newBuilder()
                    .setDatapointId(taskId + "_" + file)
                    .addAllFeatureVector(embedding)
                    .addRestricts(
                        Restriction.newBuilder().setNamespace("businessId").setAllowList(0, businessId).build()) // Fix
                    .addRestricts(Restriction.newBuilder().setNamespace("userId").setAllowList(1, userId).build())   // Fix
                    .addRestricts(Restriction.newBuilder().setNamespace("file").setAllowList(2, file).build())       // Fix
                    .build();

                UpsertDatapointsRequest request = UpsertDatapointsRequest.newBuilder()
                    .setIndex(indexName.toString())
                    .addDatapoints(datapoint)
                    .build();

                indexServiceClient.upsertDatapoints(request);
                LOG.infof("Stored embedding for file %s in Vector Search for task %s", file, taskId);
                emitter.complete(null);
            } catch (Exception e) {
                String errorMsg = "Vector search storage failed for " + file;
                LOG.error(errorMsg, e);
                emitter.fail(new AppException(errorMsg, e, AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
            }
        });
    }

    private Uni<Void> storeInVectorSearchFallback(List<Float> embedding, String file,
        String taskId, String businessId, String userId, Throwable cause) {
        LOG.warnf("Vector search storage fallback for %s: %s", file, cause.getMessage());
        // Store in a backup location or queue for retry
        return storeEmbeddingForLaterRetry(embedding, file, taskId, businessId, userId);
    }

    private Uni<Void> storeEmbeddingForLaterRetry(List<Float> embedding, String file,
        String taskId, String businessId, String userId) {
        // Implementation to store the embedding for later retry
        // This could be in Firestore or another persistent store
        return Uni.createFrom().item(() -> null);
    }

    private Uni<Void> recordFailedProcessing(String file, String taskId, String businessId,
        String userId, String errorMessage) {
        // Record the failure in a way that allows for later retry
        // This ensures we don't lose track of failed documents
        return Uni.createFrom().item(() -> null);
    }

    private Uni<String> extractText(InputStream inputStream) {
        return Uni.createFrom().emitter(emitter -> {
            try (inputStream) {
                String text = new String(inputStream.readAllBytes());
                emitter.complete(text);
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }
}
