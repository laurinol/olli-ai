/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.repository.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
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
import java.util.HashMap;
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
    
    private final MongoClient mongoClient;
    private final String databaseName;
    
    @Inject
    public MongoSyncRepository(
            MongoClient mongoClient,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli") String databaseName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        LOG.infof("MongoSyncRepository initialized with database: %s", databaseName);
    }
    
    private MongoCollection<Document> getUsersCollection() {
        return mongoClient.getDatabase(databaseName).getCollection(USERS_COLLECTION);
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
}