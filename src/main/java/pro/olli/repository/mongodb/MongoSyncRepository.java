/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.repository.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.repository.SyncRepository;

/**
 * MongoDB implementation of the SyncRepository interface.
 */
@ApplicationScoped
@Named("mongodb-sync")
public class MongoSyncRepository implements SyncRepository {

    private static final Logger LOG = Logger.getLogger(MongoSyncRepository.class);
    private static final String USERS_COLLECTION = "users";
    private static final String EMBEDDINGS_COLLECTION = "embeddings";
    
    private final MongoClient mongoClient;
    private final String databaseName;
    
    @Inject
    public MongoSyncRepository(
            MongoClient mongoClient,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli") String databaseName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        LOG.infof("MongoSyncRepository initialized with database: %s", databaseName);
        
        // Initialize the embeddings collection with necessary indexes
        if ("dev".equals(System.getProperty("quarkus.profile"))) {
            LOG.info("Development mode - initializing vector storage in MongoDB");
            getEmbeddingsCollection(); // This will create indexes
        }
    }
    
    private MongoCollection<Document> getUsersCollection() {
        return mongoClient.getDatabase(databaseName).getCollection(USERS_COLLECTION);
    }
    
    private MongoCollection<Document> getEmbeddingsCollection() {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName).getCollection(EMBEDDINGS_COLLECTION);
        
        // Ensure we have the indexes we need (only need to do this once, but MongoDB safely handles repeated calls)
        try {
            // Create index on business ID and user ID for access control
            collection.createIndex(Indexes.ascending("businessId", "userId"));
            
            // Create text index on the file path for basic text search
            collection.createIndex(Indexes.text("file"));
            
            LOG.debug("Ensured indexes on embeddings collection");
        } catch (Exception e) {
            LOG.warn("Failed to create indexes on embeddings collection", e);
        }
        
        return collection;
    }
    
    @Override
    @Retry(maxRetries = 3,
        delay = 1000,
        jitter = 200,
        maxDuration = 10000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 30000,
        successThreshold = 2)
    @CircuitBreakerName("mongodb-sync")
    public Uni<Void> appendSyncHistory(String businessId, String userId, SyncHistoryEntry entry) {
        // Validate input parameters
        if (businessId == null || businessId.isBlank()) {
            return Uni.createFrom().failure(new AppException("Business ID cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }
        if (userId == null || userId.isBlank()) {
            return Uni.createFrom().failure(new AppException("User ID cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }
        if (entry == null) {
            return Uni.createFrom().failure(new AppException("History entry cannot be null",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        LOG.infof("MongoDB: Appending sync history for business %s, user %s", businessId, userId);
        
        // Convert the SyncHistoryEntry to a MongoDB Document
        Document historyEntryDoc = new Document()
            .append("taskId", entry.getTaskId())
            .append("timestamp", entry.getTimestamp())
            .append("provider", entry.getProvider())
            .append("path", entry.getPath())
            .append("filesProcessed", entry.getFilesProcessed())
            .append("status", entry.getStatus());
            
        if (entry.getError() != null) {
            historyEntryDoc.append("error", entry.getError());
        }
        
        // Create a filter to find the user document
        Bson filter = Filters.and(
            Filters.eq("userId", userId),
            Filters.eq("businessId", businessId)
        );
        
        // Create an update to push the new entry to the syncHistory array
        Bson update = Updates.push("syncHistory", historyEntryDoc);
        
        // Options to return the updated document
        FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
            .returnDocument(ReturnDocument.AFTER)
            .upsert(true);  // Create the document if it doesn't exist
        
        // Execute the update and convert to Uni
        return Uni.createFrom().emitter(emitter -> {
            try {
                getUsersCollection().findOneAndUpdate(filter, update, options);
                LOG.debugf("Successfully updated sync history for business: %s, user: %s", businessId, userId);
                emitter.complete(null);
            } catch (Exception e) {
                LOG.errorf("Failed to append sync history: %s", e.getMessage());
                emitter.fail(new AppException("Failed to append sync history", e,
                    AppException.ErrorType.DATA_ACCESS_ERROR));
            }
        });
    }
    
    /**
     * Stores a vector embedding in MongoDB for development mode.
     * This provides a local alternative to the Vector Search API.
     */
    @Override
    @Retry(maxRetries = 2, delay = 500, jitter = 100)
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 10000)
    @CircuitBreakerName("mongodb-vector")
    public Uni<Void> storeEmbedding(String dataId, String businessId, String userId, String file, 
                                    List<Float> embedding) {
        if (!"dev".equals(System.getProperty("quarkus.profile"))) {
            LOG.debug("Not in dev mode, skipping local embedding storage");
            return Uni.createFrom().item(() -> null);
        }
        
        LOG.infof("Storing vector embedding in MongoDB (dev mode) for file: %s", file);
        
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Convert the float list to a storable format
                List<Double> vectorAsDouble = embedding.stream()
                    .map(Float::doubleValue)
                    .collect(Collectors.toList());
                
                Document embeddingDoc = new Document()
                    .append("_id", dataId)
                    .append("businessId", businessId)
                    .append("userId", userId)
                    .append("file", file)
                    .append("embeddingVector", vectorAsDouble)
                    .append("timestamp", System.currentTimeMillis());
                
                // Insert or replace the document
                getEmbeddingsCollection().replaceOne(
                    Filters.eq("_id", dataId),
                    embeddingDoc,
                    new com.mongodb.client.model.ReplaceOptions().upsert(true)
                );
                
                LOG.debugf("Successfully stored embedding in MongoDB for: %s", file);
                emitter.complete(null);
            } catch (Exception e) {
                LOG.errorf("Failed to store embedding in MongoDB: %s", e.getMessage());
                emitter.fail(new AppException("Failed to store embedding", e,
                    AppException.ErrorType.DATA_ACCESS_ERROR));
            }
        });
    }
    
    /**
     * Finds similar documents using a very basic vector similarity approach.
     * This is a development-only implementation and is not meant for production use.
     * 
     * Note: This is a naive implementation that calculates cosine similarity directly.
     * For production, use a proper vector database or Google's Vector Search API.
     */
    @Override
    @Retry(maxRetries = 2, delay = 500, jitter = 100)
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 10000)
    @CircuitBreakerName("mongodb-vector-search")
    public Uni<List<String>> findSimilarDocuments(String businessId, String userId, 
                                                 List<Float> queryEmbedding, 
                                                 int limit) {
        if (!"dev".equals(System.getProperty("quarkus.profile"))) {
            LOG.debug("Not in dev mode, skipping local embedding search");
            return Uni.createFrom().item(new ArrayList<>());
        }
        
        return Uni.createFrom().emitter(emitter -> {
            try {
                LOG.infof("Searching for similar documents in MongoDB (dev mode) for business: %s, user: %s", 
                    businessId, userId);
                
                // Create a filter for the business and user
                Bson filter = Filters.and(
                    Filters.eq("businessId", businessId),
                    Filters.eq("userId", userId)
                );
                
                // Fetch all matching documents (in production, you would use a proper vector search)
                List<Document> allDocs = getEmbeddingsCollection().find(filter)
                    .into(new ArrayList<>());
                
                LOG.debugf("Found %d candidate documents", allDocs.size());
                
                // Convert query embedding to double for calculation
                List<Double> queryVector = queryEmbedding.stream()
                    .map(Float::doubleValue)
                    .collect(Collectors.toList());
                
                // Calculate similarity for each document and sort
                List<DocumentWithSimilarity> docsWithSimilarity = new ArrayList<>();
                
                for (Document doc : allDocs) {
                    String filePath = doc.getString("file");
                    List<Double> docVector = (List<Double>) doc.get("embeddingVector");
                    
                    // Skip if no vector (shouldn't happen but just in case)
                    if (docVector == null || docVector.isEmpty()) {
                        continue;
                    }
                    
                    // Calculate cosine similarity
                    double similarity = calculateCosineSimilarity(queryVector, docVector);
                    docsWithSimilarity.add(new DocumentWithSimilarity(filePath, similarity));
                }
                
                // Sort by similarity (descending) and get top matches
                List<String> results = docsWithSimilarity.stream()
                    .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                    .limit(limit)
                    .map(d -> d.file)
                    .collect(Collectors.toList());
                
                LOG.debugf("Returning %d similar documents", results.size());
                emitter.complete(results);
            } catch (Exception e) {
                LOG.errorf("Failed to search embeddings in MongoDB: %s", e.getMessage());
                emitter.fail(new AppException("Failed to search embeddings", e,
                    AppException.ErrorType.DATA_ACCESS_ERROR));
            }
        });
    }
    
    /**
     * Helper class for storing document similarity results
     */
    private static class DocumentWithSimilarity {
        final String file;
        final double similarity;
        
        DocumentWithSimilarity(String file, double similarity) {
            this.file = file;
            this.similarity = similarity;
        }
    }
    
    /**
     * Calculates cosine similarity between two vectors
     */
    private double calculateCosineSimilarity(List<Double> vector1, List<Double> vector2) {
        if (vector1.size() != vector2.size()) {
            LOG.warnf("Vectors have different dimensions: %d vs %d", vector1.size(), vector2.size());
            return 0.0; // Cannot calculate similarity for different dimensions
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += Math.pow(vector1.get(i), 2);
            norm2 += Math.pow(vector2.get(i), 2);
        }
        
        // Avoid division by zero
        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}