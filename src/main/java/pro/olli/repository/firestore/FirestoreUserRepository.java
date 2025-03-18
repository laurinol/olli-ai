/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.repository.firestore;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
import pro.olli.models.Invite;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.User;
import pro.olli.repository.UserRepository;

@ApplicationScoped
@Named("firestore-user")
public class FirestoreUserRepository implements UserRepository {

    private static final Logger LOG = Logger.getLogger(FirestoreUserRepository.class);
    private final Firestore firestore;

    @Inject
    public FirestoreUserRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    // User mapping methods
    private User mapToUser(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) {
            return null;
        }

        Map<String, Object> data = doc.getData();
        if (data == null) {
            return null;
        }

        // Extract user data
        String id = (String) data.getOrDefault("id", doc.getId());
        String email = (String) data.get("email");
        String name = (String) data.get("name");
        String avatarUrl = (String) data.get("avatarUrl");

        // Handle timestamps
        Instant createdAt = null;
        if (data.get("createdAt") instanceof Long) {
            createdAt = Instant.ofEpochMilli((Long) data.get("createdAt"));
        }

        Instant updatedAt = null;
        if (data.get("updatedAt") instanceof Long) {
            updatedAt = Instant.ofEpochMilli((Long) data.get("updatedAt"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");

        return new User(id, email, name, avatarUrl,
                       createdAt != null ? createdAt : Instant.now(),
                       updatedAt != null ? updatedAt : Instant.now(),
                       metadata, null); // Never store password in user object
    }

    private Map<String, Object> userToMap(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("email", user.getEmail());

        if (user.getName() != null) {
            data.put("name", user.getName());
        }

        if (user.getAvatarUrl() != null) {
            data.put("avatarUrl", user.getAvatarUrl());
        }

        data.put("createdAt", user.getCreatedAt().toEpochMilli());
        data.put("updatedAt", user.getUpdatedAt().toEpochMilli());

        if (user.getMetadata() != null) {
            data.put("metadata", user.getMetadata());
        }

        return data;
    }

    // User methods
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
    @CircuitBreakerName("firestore-user-save")
    public Uni<Void> saveUser(User user) {
        if (user == null) {
            return Uni.createFrom().failure(new AppException("User cannot be null",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return Uni.createFrom().failure(new AppException("User email cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        LOG.infof("Firestore: Saving user with email: %s", user.getEmail());

        Map<String, Object> userData = userToMap(user);

        return Uni.createFrom().future(
            firestore.collection("users").document(user.getEmail()).set(userData)
        ).map(ignored -> {
            LOG.debugf("Successfully saved user with email: %s", user.getEmail());
            return (Void) null;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to save user with email %s: %s", user.getEmail(), e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to save user", e,
            AppException.ErrorType.DATA_ACCESS_ERROR));
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
    @CircuitBreakerName("firestore-user-get")
    public Uni<Optional<User>> getUser(String email) {
        if (email == null || email.isBlank()) {
            return Uni.createFrom().failure(new AppException("Email cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        LOG.infof("Firestore: Retrieving user with email: %s", email);

        return Uni.createFrom().future(
            firestore.collection("users").document(email).get()
        ).map(doc -> {
            User user = mapToUser(doc);
            LOG.debugf("Retrieved user with email: %s, exists: %s", email, user != null);
            return Optional.ofNullable(user);
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve user with email %s: %s", email, e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to retrieve user", e,
            AppException.ErrorType.DATA_ACCESS_ERROR));
    }

    // Invite mapping methods
    private Invite mapToInvite(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) {
            return null;
        }

        Map<String, Object> data = doc.getData();
        if (data == null) {
            return null;
        }

        String token = (String) data.getOrDefault("token", doc.getId());
        String businessId = (String) data.get("businessId");
        String email = (String) data.get("email");
        String role = (String) data.get("role");
        String invitedBy = (String) data.get("invitedBy");
        boolean claimed = Boolean.TRUE.equals(data.get("claimed"));

        Instant createdAt = null;
        if (data.get("createdAt") instanceof Long) {
            createdAt = Instant.ofEpochMilli((Long) data.get("createdAt"));
        }

        Instant expiresAt = null;
        if (data.get("expiresAt") instanceof Long) {
            expiresAt = Instant.ofEpochMilli((Long) data.get("expiresAt"));
        }

        return new Invite(token, businessId, email, role, invitedBy,
                        createdAt != null ? createdAt : Instant.now(),
                        expiresAt != null ? expiresAt : Instant.now().plusSeconds(7 * 24 * 60 * 60),
                        claimed);
    }

    private Map<String, Object> inviteToMap(Invite invite) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", invite.getToken());
        data.put("businessId", invite.getBusinessId());
        data.put("email", invite.getEmail());

        if (invite.getRole() != null) {
            data.put("role", invite.getRole());
        }

        if (invite.getInvitedBy() != null) {
            data.put("invitedBy", invite.getInvitedBy());
        }

        data.put("createdAt", invite.getCreatedAt().toEpochMilli());
        data.put("expiresAt", invite.getExpiresAt().toEpochMilli());
        data.put("claimed", invite.isClaimed());

        return data;
    }

    // Invite methods
    @Override
    public Uni<Void> saveInvite(Invite invite) {
        LOG.infof("Firestore: Saving invite with token: %s, claimed: %s", invite.getToken(), invite.isClaimed());
        
        // Check if we're updating an existing invite to claimed=true
        boolean updatingToClaimedState = invite.isClaimed();
        if (updatingToClaimedState) {
            LOG.infof("Firestore: CRITICAL - Attempting to update invite to claimed=true for token: %s", 
                invite.getToken());
        }
        
        Map<String, Object> inviteData = inviteToMap(invite);
        LOG.infof("Firestore: Invite data to save: %s", inviteData);

        return Uni.createFrom().future(
            firestore.collection("invites").document(invite.getToken()).set(inviteData)
        ).map(ignored -> {
            LOG.infof("Firestore: Successfully saved invite with token: %s, claimed: %s", 
                invite.getToken(), invite.isClaimed());
            return (Void) null;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to save invite with token %s: %s", invite.getToken(), e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to save invite", e,
            AppException.ErrorType.DATA_ACCESS_ERROR));
    }

    @Override
    public Uni<Optional<Invite>> getInvite(String token) {
        LOG.infof("Firestore: Retrieving invite with token: %s", token);
        
        return Uni.createFrom().future(
            firestore.collection("invites").document(token).get()
        ).map(doc -> {
            Invite invite = mapToInvite(doc);
            LOG.debugf("Retrieved invite with token: %s, exists: %s", token, invite != null);
            return Optional.ofNullable(invite);
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve invite with token %s: %s", token, e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to retrieve invite", e,
            AppException.ErrorType.DATA_ACCESS_ERROR));
    }

    @Override
    public Uni<Void> deleteInvite(String token) {
        LOG.infof("Firestore: Deleting invite with token: %s", token);
        
        return Uni.createFrom().future(
            firestore.collection("invites").document(token).delete()
        ).map(ignored -> {
            LOG.debugf("Successfully deleted invite with token: %s", token);
            return (Void) null;
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to delete invite with token %s: %s", token, e.getMessage()))
        .onFailure().transform(e -> new AppException("Failed to delete invite", e,
            AppException.ErrorType.DATA_ACCESS_ERROR));
    }

    @Override
    public Uni<Optional<Invite>> getInviteByEmail(String email) {
        LOG.infof("Firestore: Retrieving invite for email: %s", email);
        
        // Query by email field and sort by createdAt to get the most recent
        return Uni.createFrom().future(
            firestore.collection("invites")
                    .whereEqualTo("email", email)
                    .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
        ).map(querySnapshot -> {
            if (querySnapshot.isEmpty()) {
                LOG.infof("Firestore: No invites found for email: %s", email);
                return Optional.<Invite>empty();
            }
            
            DocumentSnapshot doc = querySnapshot.getDocuments().get(0);
            Invite invite = mapToInvite(doc);
            LOG.infof("Firestore: Retrieved invite for email %s, token: %s, claimed: %s", 
                email, invite.getToken(), invite.isClaimed());
            return Optional.<Invite>of(invite);
        })
        .onFailure().invoke(e -> LOG.errorf("Failed to retrieve invite for email %s: %s", email, e.getMessage()))
        .onFailure().recoverWithItem(e -> {
            LOG.errorf("Failed to retrieve invite by email: %s", e.getMessage());
            return Optional.<Invite>empty();
        });
    }

    // Business methods - empty placeholder implementations
    @Override
    public Uni<Optional<Business>> getBusiness(String businessId) {
        LOG.infof("Firestore: Getting business with ID: %s", businessId);
        return Uni.createFrom().item(Optional.empty());
    }

    @Override 
    public Uni<Void> saveBusiness(Business business) {
        LOG.infof("Firestore: Saving business with ID: %s", business.getId());
        return Uni.createFrom().item(() -> null);
    }

    @Override
    public Uni<Void> saveBusinessUser(BusinessUser businessUser) {
        LOG.infof("Firestore: Saving business user with ID: %s for business: %s", 
                businessUser.getId(), businessUser.getBusinessId());
        return Uni.createFrom().item(() -> null);
    }

    @Override
    public Uni<Optional<BusinessUser>> getBusinessUser(String businessId, String userId) {
        LOG.infof("Firestore: Getting business user with ID: %s for business: %s", userId, businessId);
        return Uni.createFrom().item(Optional.empty());
    }

    @Override
    public Uni<Void> updateBusinessUserSyncHistory(String businessId, String userId, BusinessUser updatedUser) {
        LOG.infof("Firestore: Updating history for user with ID: %s for business: %s", userId, businessId);
        return Uni.createFrom().item(() -> null);
    }
}