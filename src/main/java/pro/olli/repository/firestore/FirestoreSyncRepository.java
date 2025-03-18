/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository.firestore;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.util.Map;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.repository.SyncRepository;

@ApplicationScoped
@Named("firestore-sync")
public class FirestoreSyncRepository implements SyncRepository {

    private static final Logger LOG = Logger.getLogger(FirestoreSyncRepository.class);
    private final Firestore firestore;

    @Inject
    public FirestoreSyncRepository(Firestore firestore) {
        this.firestore = firestore;
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
    @CircuitBreakerName("firestore-sync")
    public Uni<Void> appendSyncHistory(String businessId, String userId, SyncHistoryEntry historyEntry) {
        // Validate input parameters
        if (businessId == null || businessId.isBlank()) {
            return Uni.createFrom().failure(new AppException("Business ID cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }
        if (userId == null || userId.isBlank()) {
            return Uni.createFrom().failure(new AppException("User ID cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }
        if (historyEntry == null) {
            return Uni.createFrom().failure(new AppException("History entry cannot be null",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        LOG.infof("Appending sync history for business: %s, user: %s", businessId, userId);

        return Uni.createFrom().future(
                firestore.collection("clients").document(businessId)
                    .collection("users").document(userId)
                    .update("syncHistory", FieldValue.arrayUnion(historyEntry))
            ).map(ignored -> {
                LOG.debugf("Successfully updated sync history for business: %s, user: %s", businessId, userId);
                return (Void) null;
            })
            .onFailure().invoke(e -> LOG.warnf("Failed to update sync history, will attempt to create document: %s", e.getMessage()))
            .onFailure().recoverWithUni(() ->
                Uni.createFrom().future(
                    firestore.collection("clients").document(businessId)
                        .collection("users").document(userId)
                        .set(Map.of("syncHistory", java.util.Collections.singletonList(historyEntry)))
                ).map(ignored -> {
                    LOG.infof("Created new sync history document for business: %s, user: %s", businessId, userId);
                    return (Void) null;
                })
                .onFailure().transform(e -> {
                    LOG.errorf("Failed to create sync history: %s", e.getMessage());
                    return new AppException("Failed to append sync history", e,
                        AppException.ErrorType.DATA_ACCESS_ERROR);
                })
            );
    }
}