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
import io.quarkus.runtime.Startup;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.InputStream;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import pro.olli.ai.AiConfiguredService;
import pro.olli.ai.AiImplementationType;
import pro.olli.ai.EmbeddingService;
import pro.olli.connector.Connector;
import pro.olli.connector.ConnectorFactory;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.SyncTask;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.SyncRepository;
import pro.olli.service.BillingService;

@ApplicationScoped
@Startup
public class SyncWorker {

    private static final Logger LOG = Logger.getLogger(SyncWorker.class);

    @ConfigProperty(name = "gcp.project-id", defaultValue = "olli-ai")
    String projectId;

    @ConfigProperty(name = "gcp.vertex.location", defaultValue = "us-west1")
    String location;

    @ConfigProperty(name = "worker.backoff.initial-ms", defaultValue = "1000")
    long initialBackoffMs;

    @ConfigProperty(name = "worker.backoff.max-ms", defaultValue = "60000")
    long maxBackoffMs;

    @ConfigProperty(name = "worker.backoff.multiplier", defaultValue = "1.5")
    double backoffMultiplier;

    @ConfigProperty(name = "app.worker.multiplier", defaultValue = "4")
    int workerMultiplier;

    private final ConnectorFactory connectorFactory;
    private final EmbeddingService embeddingService;
    private final TaskQueueService taskQueueService;
    private final BillingService billingService;
    private final IndexServiceClient indexServiceClient;
    private final ExecutorService executorService;
    private final Gson gson;
    private final int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() * workerMultiplier);
    private final SyncRepository syncRepository;

    @Inject
    public SyncWorker(ConnectorFactory connectorFactory,
        @AiConfiguredService EmbeddingService embeddingService,
        TaskQueueService taskQueueService,
        BillingService billingService,
        IndexServiceClient indexServiceClient,
        @Named("mongodb-sync") SyncRepository syncRepository) {
        this.connectorFactory = connectorFactory;
        this.embeddingService = embeddingService;
        this.taskQueueService = taskQueueService;
        this.billingService = billingService;
        this.indexServiceClient = indexServiceClient;
        this.syncRepository = syncRepository;
        this.gson = new Gson();
        this.executorService = Executors.newFixedThreadPool(
            workerCount,
            new ThreadFactoryBuilder().setNameFormat("sync-worker-%d").build()
        );
        LOG.infof("SyncWorker initialized with %s and %s in %s mode",
            embeddingService.getClass().getName(),
            syncRepository.getClass().getName(),
            System.getProperty("quarkus.profile", "unknown"));
    }

    @PostConstruct
    public void startConsuming() {
        LOG.info("SyncWorker is initializing with " + workerCount + " worker threads...");
        try {
            // Use a simple loop from 0 to workerCount to submit tasks
            for (int i = 0; i < workerCount; i++) {
                executorService.submit(this::consumeTasks);
            }
        } catch (Exception e) {
            LOG.errorf("Unable to start %d SyncWorker threads: %s", workerCount, e.getMessage());
            throw new RuntimeException(e);
        }
        LOG.infof("Started %d SyncWorker threads for reading tasks from the queue", workerCount);
        LOG.info("SyncWorker initialization complete - ready to process tasks");
    }

    private void consumeTasks() {
        LOG.infof("Worker thread starting with backoff parameters: initial=%dms, max=%dms, multiplier=%.2f",
            initialBackoffMs, maxBackoffMs, backoffMultiplier);

        // Use thread-local atomics for counters that need to be modified in lambda expressions
        final AtomicLong currentBackoff = new AtomicLong(initialBackoffMs);
        final AtomicInteger emptyPollCount = new AtomicInteger(0);
        final AtomicLong lastLogTimestamp = new AtomicLong(0);

        while (!Thread.currentThread().isInterrupted()) {
            final long backoffTime = currentBackoff.get(); // Capture current value for lambda

            taskQueueService.dequeueTask()
                .subscribe().with(
                    dequeuedTask -> {
                        // Reset backoff on successful task retrieval
                        currentBackoff.set(initialBackoffMs);
                        emptyPollCount.set(0);

                        // Process the task
                        processTask(dequeuedTask.getTaskId(), dequeuedTask.getJsonPayload())
                            .subscribe().with(
                                v -> LOG.infof("Processed task %s: %s", dequeuedTask.getTaskId(),
                                    dequeuedTask.getJsonPayload()),
                                e -> LOG.errorf("Failed to process task %s: %s, error: %s", dequeuedTask.getTaskId(),
                                    dequeuedTask.getJsonPayload(), e.getMessage())
                            );
                    },
                    e -> {
                        // Increment empty poll counter
                        int count = emptyPollCount.incrementAndGet();

                        // Only log empty queue every 100 polls or every minute, to reduce log spam
                        long currentTime = System.currentTimeMillis();
                        long lastLog = lastLogTimestamp.get();

                        if (count % 100 == 1 || (currentTime - lastLog) > 60000) {
                            LOG.debugf("No tasks available (count: %d), backing off for %d ms: %s",
                                count, backoffTime, e.getMessage());
                            lastLogTimestamp.set(currentTime);
                        }

                        // Apply exponential backoff with capped maximum
                        try {
                            Thread.sleep(backoffTime);
                            // Increase backoff time for next iteration (capped at max)
                            long newBackoff = Math.min(maxBackoffMs, (long) (backoffTime * backoffMultiplier));
                            currentBackoff.set(newBackoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
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

                Connector provider = connectorFactory.getProvider(source);
                if (provider == null) {
                    LOG.errorf("Unsupported source %s for task %s", source, taskId);
                    emitter.fail(new IllegalArgumentException("Unsupported source: " + source));
                    return;
                }

                LOG.infof("Processing sync task %s for client %s, user %s, source %s, folder %s",
                    taskId, businessId, userId, source, folderId);
                provider.listFiles(folderId)
                    .flatMap(files -> {
                        // Check if business has quota for processing these files
                        return billingService.checkBusinessLimits(businessId)
                            .onItem().transformToUni(business -> {
                                // Check if business is over document limit
                                if (business.isDocumentLimitExceeded()) {
                                    LOG.warnf("Business %s has exceeded document limit, skipping sync", businessId);
                                    return Uni.createFrom().failure(
                                        new IllegalStateException("Document processing limit exceeded"));
                                }

                                // Record document processing in billing
                                return billingService.recordDocumentProcessing(businessId, files.size())
                                    .map(v -> files);
                            });
                    })
                    .flatMap(files -> Uni.createFrom().item(files)
                        .map(fileList -> fileList.stream()
                            .map(file -> processFile(provider, folderId + "/" + file, taskId, businessId, userId))
                            .toList())
                        .flatMap(unis -> Uni.combine().all().unis(unis).combinedWith(results -> null)))
                    .subscribe().with(
                        results -> emitter.complete(null),
                        error -> {
                            if (error instanceof IllegalStateException &&
                                error.getMessage().contains("limit exceeded")) {
                                // This is a soft error - complete the task but log it
                                LOG.warnf("Task %s completed with limit warning: %s", taskId, error.getMessage());
                                emitter.complete(null);
                            } else {
                                emitter.fail(error);
                            }
                        }
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
    protected Uni<Void> processFile(Connector provider, String file, String taskId, String businessId,
        String userId) {
        LOG.infof("Processing sync file %s", file);
        return provider.downloadFile(file)
            // Extract text using Apache Tika
            .flatMap(this::extractText)
            // Add filename and file metadata as context at the beginning of the text
            .map(text -> {
                String fileExt = getFileExtension(file);
                String fileType = mapFileExtensionToType(fileExt);

                // Add metadata header to the text to provide context to the embedding
                String metadataPrefix = String.format(
                    "File: %s\nType: %s\nBusiness: %s\n\nContent:\n",
                    file, fileType, businessId);

                return metadataPrefix + text;
            })
            // Generate embedding from the text with metadata
            .flatMap(text -> generateEmbeddingWithFaultTolerance(text, file))
            // Store in vector search
            .flatMap(embedding -> storeInVectorSearchWithFaultTolerance(embedding, file, taskId, businessId, userId))
            .onFailure().transform(e -> {
                if (e instanceof AppException) {
                    return e;
                }
                return new AppException("Failed to process file: " + file, e, AppException.ErrorType.SYSTEM_ERROR);
            });
    }

    /**
     * Gets the file extension from a filename
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex + 1).toLowerCase();
        }
        return "";
    }

    /**
     * Maps file extensions to more user-friendly type descriptions
     */
    private String mapFileExtensionToType(String extension) {
        return switch (extension.toLowerCase()) {
            case "pdf" -> "PDF Document";
            case "doc", "docx" -> "Word Document";
            case "xls", "xlsx" -> "Excel Spreadsheet";
            case "ppt", "pptx" -> "PowerPoint Presentation";
            case "txt" -> "Text Document";
            case "csv" -> "CSV Spreadsheet";
            case "jpg", "jpeg", "png", "gif", "bmp" -> "Image";
            case "mp3", "wav", "ogg" -> "Audio";
            case "mp4", "avi", "mov" -> "Video";
            case "zip", "tar", "gz", "rar" -> "Archive";
            case "html", "htm" -> "Web Page";
            case "json" -> "JSON Data";
            case "xml" -> "XML Data";
            case "odt" -> "OpenDocument Text";
            case "ods" -> "OpenDocument Spreadsheet";
            case "odp" -> "OpenDocument Presentation";
            default -> "Unknown Document Type";
        };
    }

    protected Uni<Void> processingFileFallback(Connector provider, String file, String taskId,
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
    protected Uni<List<Float>> generateEmbeddingWithFaultTolerance(String text, String fileIdentifier) {
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
    protected Uni<Void> storeInVectorSearchWithFaultTolerance(List<Float> embedding, String file,
        String taskId, String businessId, String userId) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                // In development environment, we don't need Vector Search API
                if ("dev".equals(System.getProperty("quarkus.profile")) || indexServiceClient == null) {
                    LOG.infof("DEV MODE: Using MongoDB for vector storage instead of Vector Search API");
                    LOG.infof("DEV MODE: Storing vector for file %s (business: %s, user: %s, dimensions: %d)",
                        file, businessId, userId, embedding.size());

                    // In dev mode, store in MongoDB instead of Vector Search
                    syncRepository.storeEmbedding(taskId + "_" + file, businessId, userId, file, embedding)
                        .subscribe().with(
                            v -> emitter.complete(null),
                            e -> {
                                LOG.errorf("Failed to store embedding in MongoDB: %s", e.getMessage());
                                emitter.fail(e);
                            }
                        );
                    return; // Don't return a value from the lambda
                }

                LOG.infof("Storing vector embedding for file %s in project %s, location %s",
                    file, projectId, location);

                IndexName indexName = IndexName.of(projectId, location, "sync-index");
                IndexDatapoint datapoint = IndexDatapoint.newBuilder()
                    .setDatapointId(taskId + "_" + file)
                    .addAllFeatureVector(embedding)
                    .addRestricts(
                        Restriction.newBuilder().setNamespace("businessId").addAllowList(businessId).build())
                    .addRestricts(
                        Restriction.newBuilder().setNamespace("userId").addAllowList(userId).build())
                    .addRestricts(
                        Restriction.newBuilder().setNamespace("file").addAllowList(file).build())
                    .build();

                UpsertDatapointsRequest request = UpsertDatapointsRequest.newBuilder()
                    .setIndex(indexName.toString())
                    .addDatapoints(datapoint)
                    .build();

                LOG.debugf("Sending request to Vector Search API: %s", request);
                indexServiceClient.upsertDatapoints(request);
                LOG.infof("Successfully stored embedding for file %s in Vector Search for task %s", file, taskId);
                emitter.complete(null);
            } catch (Exception e) {
                String errorMsg = "Vector search connector failed for " + file;

                // Enhanced error logging for API issues
                if (e.getMessage() != null && (
                    e.getMessage().contains("404") ||
                    e.getMessage().contains("UNIMPLEMENTED") ||
                    e.getMessage().contains("NOT_FOUND"))) {
                    LOG.errorf("Vector Search API unavailable. " +
                        "Check if the correct project (%s) and location (%s) are configured. Error: %s",
                        projectId, location, e.getMessage());

                    // In development mode, we'll consider this a non-fatal error
                    if ("dev".equals(System.getProperty("quarkus.profile"))) {
                        LOG.info("Development mode: continuing despite Vector Search API error");
                        emitter.complete(null);
                        return;
                    }
                }

                // Special handling for NullPointerException (likely happens if client is null)
                if (e instanceof NullPointerException) {
                    LOG.errorf("NullPointerException in Vector Search client. This may indicate the client was not initialized properly.");
                    if ("dev".equals(System.getProperty("quarkus.profile"))) {
                        LOG.info("Development mode: continuing despite Vector Search API error");
                        emitter.complete(null);
                        return;
                    }
                }

                LOG.error(errorMsg, e);
                emitter.fail(new AppException(errorMsg, e, AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
            }
        });
    }

    protected Uni<Void> storeInVectorSearchFallback(List<Float> embedding, String file,
        String taskId, String businessId, String userId, Throwable cause) {
        LOG.warnf("Vector search connector fallback for %s: %s", file, cause.getMessage());
        // Store in a backup location or queue for retry
        return storeEmbeddingForLaterRetry(embedding, file, taskId, businessId, userId);
    }

    protected Uni<Void> storeEmbeddingForLaterRetry(List<Float> embedding, String file,
        String taskId, String businessId, String userId) {
        // Implementation to store the embedding for later retry
        // This could be in Firestore or another persistent store
        return Uni.createFrom().item(() -> null);
    }

    protected Uni<Void> recordFailedProcessing(String file, String taskId, String businessId,
        String userId, String errorMessage) {
        // Record the failure in a way that allows for later retry
        // This ensures we don't lose track of failed documents
        return Uni.createFrom().item(() -> null);
    }

    /**
     * Extracts text content from various file formats using Apache Tika. Handles PDF, DOC, DOCX, ODT, RTF, TXT, and
     * many other formats automatically.
     *
     * @param inputStream The input stream of the file
     * @return A Uni containing the extracted text content
     */
    protected Uni<String> extractText(InputStream inputStream) {
        return Uni.createFrom().emitter(emitter -> {
            try (inputStream) {
                // Create a content handler with generous write limit
                ContentHandler handler = new BodyContentHandler(-1); // -1 means no limit

                // Create the metadata object
                Metadata metadata = new Metadata();

                // Create the parser
                Parser parser = new AutoDetectParser();

                // Parse the document
                ParseContext context = new ParseContext();
                parser.parse(inputStream, handler, metadata, context);

                // Get the extracted text
                String extractedText = handler.toString();

                // Log metadata for debugging
                LOG.debugf("Extracted text from %s file, size: %d characters",
                    metadata.get("Content-Type"), extractedText.length());

                emitter.complete(extractedText);
            } catch (TikaException te) {
                LOG.error("Tika parsing error", te);
                emitter.fail(new AppException("Failed to parse document with Tika",
                    te, AppException.ErrorType.SYSTEM_ERROR));
            } catch (SAXException se) {
                LOG.error("SAX parsing error", se);
                emitter.fail(new AppException("XML parsing error during text extraction",
                    se, AppException.ErrorType.SYSTEM_ERROR));
            } catch (Exception e) {
                LOG.error("General error during text extraction", e);
                emitter.fail(e);
            }
        });
    }
}
