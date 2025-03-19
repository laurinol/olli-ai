/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.repository.firestore;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.Invitation;
import pro.olli.repository.InvitationRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Firestore implementation of the InvitationRepository interface.
 */
@ApplicationScoped
@Named("firestore-invitation")
public class FirestoreInvitationRepository implements InvitationRepository {

    private static final Logger LOG = Logger.getLogger(FirestoreInvitationRepository.class);
    private final Firestore firestore;

    @Inject
    public FirestoreInvitationRepository(Firestore firestore) {
        this.firestore = firestore;
        LOG.info("FirestoreInvitationRepository initialized");
    }

    // Document mapping methods
    private Map<String, Object> invitationToMap(Invitation invitation) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", invitation.getId());
        data.put("businessId", invitation.getBusinessId());
        data.put("email", invitation.getEmail());

        if (invitation.getRole() != null) {
            data.put("role", invitation.getRole());
        }

        if (invitation.getSentByUserId() != null) {
            data.put("sentByUserId", invitation.getSentByUserId());
        }

        data.put("createdAt", invitation.getCreatedAt().toEpochMilli());
        data.put("expiresAt", invitation.getExpiresAt().toEpochMilli());
        data.put("accepted", invitation.isAccepted());

        return data;
    }

    private Invitation mapToInvitation(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) {
            return null;
        }

        Map<String, Object> data = doc.getData();
        if (data == null) {
            return null;
        }

        String id = (String) data.getOrDefault("id", doc.getId());
        String businessId = (String) data.get("businessId");
        String email = (String) data.get("email");
        String role = (String) data.get("role");
        String sentByUserId = (String) data.get("sentByUserId");
        boolean accepted = Boolean.TRUE.equals(data.get("accepted"));

        Instant createdAt = null;
        if (data.get("createdAt") instanceof Long) {
            createdAt = Instant.ofEpochMilli((Long) data.get("createdAt"));
        }

        Instant expiresAt = null;
        if (data.get("expiresAt") instanceof Long) {
            expiresAt = Instant.ofEpochMilli((Long) data.get("expiresAt"));
        }

        return new Invitation(id, businessId, email, role, sentByUserId,
                createdAt != null ? createdAt : Instant.now(),
                expiresAt != null ? expiresAt : Instant.now().plusSeconds(7 * 24 * 60 * 60),
                accepted);
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
    @CircuitBreakerName("firestore-invitation-save")
    public Uni<Void> saveInvitation(Invitation invitation) {
        LOG.infof("Firestore: Saving invitation with ID: %s", invitation.getId());
        
        if (invitation == null) {
            return Uni.createFrom().failure(new AppException("Invitation cannot be null",
                    AppException.ErrorType.VALIDATION_ERROR));
        }

        Map<String, Object> data = invitationToMap(invitation);
        
        return Uni.createFrom().future(
                firestore.collection("invitations").document(invitation.getId()).set(data)
        ).map(ignored -> {
            LOG.debugf("Successfully saved invitation with ID: %s", invitation.getId());
            return (Void) null;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to save invitation with ID %s: %s", invitation.getId(), e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to save invitation", e, AppException.ErrorType.DATA_ACCESS_ERROR));
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("firestore-invitation-get")
    public Uni<Optional<Invitation>> getInvitation(String id) {
        LOG.infof("Firestore: Retrieving invitation with ID: %s", id);
        
        return Uni.createFrom().future(
                firestore.collection("invitations").document(id).get()
        ).map(doc -> {
            Invitation invitation = mapToInvitation(doc);
            LOG.debugf("Retrieved invitation with ID: %s, exists: %s", id, invitation != null);
            return Optional.ofNullable(invitation);
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve invitation with ID %s: %s", id, e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to retrieve invitation", e,
                AppException.ErrorType.DATA_ACCESS_ERROR));
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("firestore-invitation-list-business")
    public Uni<List<Invitation>> getInvitationsByBusiness(String businessId) {
        LOG.infof("Firestore: Retrieving invitations for business: %s", businessId);
        
        return Uni.createFrom().future(
                firestore.collection("invitations")
                        .whereEqualTo("businessId", businessId)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .get()
        ).map(querySnapshot -> {
            List<Invitation> invitations = new ArrayList<>();
            for (QueryDocumentSnapshot document : querySnapshot.getDocuments()) {
                Invitation invitation = mapToInvitation(document);
                if (invitation != null) {
                    invitations.add(invitation);
                }
            }
            LOG.infof("Found %d invitations for business: %s", invitations.size(), businessId);
            return invitations;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve invitations for business %s: %s", businessId, e.getMessage()))
        .onFailure().recoverWithItem(e -> {
            LOG.errorf("Error retrieving invitations by business: %s", e.getMessage());
            return new ArrayList<>();
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("firestore-invitation-list-email")
    public Uni<List<Invitation>> getInvitationsByEmail(String email) {
        LOG.infof("Firestore: Retrieving invitations for email: %s", email);
        
        return Uni.createFrom().future(
                firestore.collection("invitations")
                        .whereEqualTo("email", email)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .get()
        ).map(querySnapshot -> {
            List<Invitation> invitations = new ArrayList<>();
            for (QueryDocumentSnapshot document : querySnapshot.getDocuments()) {
                Invitation invitation = mapToInvitation(document);
                if (invitation != null) {
                    invitations.add(invitation);
                }
            }
            LOG.infof("Found %d invitations for email: %s", invitations.size(), email);
            return invitations;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve invitations for email %s: %s", email, e.getMessage()))
        .onFailure().recoverWithItem(e -> {
            LOG.errorf("Error retrieving invitations by email: %s", e.getMessage());
            return new ArrayList<>();
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("firestore-invitation-list-sender")
    public Uni<List<Invitation>> getInvitationsBySender(String userId) {
        LOG.infof("Firestore: Retrieving invitations sent by user: %s", userId);
        
        return Uni.createFrom().future(
                firestore.collection("invitations")
                        .whereEqualTo("sentByUserId", userId)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .get()
        ).map(querySnapshot -> {
            List<Invitation> invitations = new ArrayList<>();
            for (QueryDocumentSnapshot document : querySnapshot.getDocuments()) {
                Invitation invitation = mapToInvitation(document);
                if (invitation != null) {
                    invitations.add(invitation);
                }
            }
            LOG.infof("Found %d invitations sent by user: %s", invitations.size(), userId);
            return invitations;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve invitations sent by user %s: %s", userId, e.getMessage()))
        .onFailure().recoverWithItem(e -> {
            LOG.errorf("Error retrieving invitations by sender: %s", e.getMessage());
            return new ArrayList<>();
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("firestore-invitation-list-pending")
    public Uni<List<Invitation>> getPendingInvitations(String businessId) {
        LOG.infof("Firestore: Retrieving pending invitations for business: %s", businessId);
        
        return Uni.createFrom().future(
                firestore.collection("invitations")
                        .whereEqualTo("businessId", businessId)
                        .whereEqualTo("accepted", false)
                        .get()
        ).map(querySnapshot -> {
            List<Invitation> invitations = new ArrayList<>();
            long now = Instant.now().toEpochMilli();
            
            for (QueryDocumentSnapshot document : querySnapshot.getDocuments()) {
                // Manual filtering for expiration date because Firestore doesn't directly support
                // timestamp comparison in queries
                if (document.contains("expiresAt") && document.getLong("expiresAt") > now) {
                    Invitation invitation = mapToInvitation(document);
                    if (invitation != null) {
                        invitations.add(invitation);
                    }
                }
            }
            
            LOG.infof("Found %d pending invitations for business: %s", invitations.size(), businessId);
            return invitations;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve pending invitations for business %s: %s", businessId, e.getMessage()))
        .onFailure().recoverWithItem(e -> {
            LOG.errorf("Error retrieving pending invitations: %s", e.getMessage());
            return new ArrayList<>();
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("firestore-invitation-delete")
    public Uni<Void> deleteInvitation(String id) {
        LOG.infof("Firestore: Deleting invitation with ID: %s", id);
        
        return Uni.createFrom().future(
                firestore.collection("invitations").document(id).delete()
        ).map(ignored -> {
            LOG.debugf("Successfully deleted invitation with ID: %s", id);
            return (Void) null;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to delete invitation with ID %s: %s", id, e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to delete invitation", e, AppException.ErrorType.DATA_ACCESS_ERROR));
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("firestore-user-is-admin")
    public Uni<Boolean> isUserAdmin(String userId, String businessId) {
        LOG.infof("Firestore: Checking if user %s is admin for business %s", userId, businessId);
        
        return Uni.createFrom().future(
                firestore.collection("business_users")
                        .whereEqualTo("id", userId)
                        .whereEqualTo("businessId", businessId)
                        .whereEqualTo("role", "admin")
                        .get()
        ).map(querySnapshot -> {
            boolean isAdmin = !querySnapshot.isEmpty();
            LOG.infof("User %s is admin for business %s: %s", userId, businessId, isAdmin);
            return isAdmin;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to check if user %s is admin: %s", userId, e.getMessage()))
        .onFailure().recoverWithItem(e -> {
            LOG.errorf("Error checking admin status: %s", e.getMessage());
            return false;
        });
    }
}