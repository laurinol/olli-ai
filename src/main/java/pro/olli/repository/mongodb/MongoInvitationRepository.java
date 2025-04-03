/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.repository.mongodb;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.config.AppConfig;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.Invitation;
import pro.olli.repository.InvitationRepository;

/**
 * MongoDB implementation of the InvitationRepository interface.
 */
public class MongoInvitationRepository implements InvitationRepository {

    private static final Logger LOG = Logger.getLogger(MongoInvitationRepository.class);
    private static final String INVITATIONS_COLLECTION = "invitations";
    private static final String BUSINESS_USERS_COLLECTION = "business_users";

    private final AppConfig config;
    private final MongoClient mongoClient;

    @Inject
    public MongoInvitationRepository(AppConfig config, MongoClient mongoClient) {
        this.config = config;
        this.mongoClient = mongoClient;
        LOG.infof("MongoInvitationRepository initialized with database: %s", config.getMongoDbName());
    }

    // Helper methods to get the collections
    private MongoCollection<Document> getInvitationsCollection() {
        return mongoClient.getDatabase(config.getMongoDbName()).getCollection(INVITATIONS_COLLECTION);
    }

    private MongoCollection<Document> getBusinessUsersCollection() {
        return mongoClient.getDatabase(config.getMongoDbName()).getCollection(BUSINESS_USERS_COLLECTION);
    }

    // Document mapping methods
    private Document invitationToDocument(Invitation invitation) {
        Document doc = new Document();
        doc.append("id", invitation.getId());
        doc.append("businessId", invitation.getBusinessId());
        doc.append("email", invitation.getEmail());

        if (invitation.getRole() != null) {
            doc.append("role", invitation.getRole());
        }

        if (invitation.getSentByUserId() != null) {
            doc.append("sentByUserId", invitation.getSentByUserId());
        }

        doc.append("createdAt", invitation.getCreatedAt().toEpochMilli());
        doc.append("expiresAt", invitation.getExpiresAt().toEpochMilli());
        doc.append("accepted", invitation.isAccepted());

        return doc;
    }

    private Invitation documentToInvitation(Document doc) {
        if (doc == null) {
            return null;
        }

        String id = doc.getString("id");
        String businessId = doc.getString("businessId");
        String email = doc.getString("email");
        String role = doc.getString("role");
        String sentByUserId = doc.getString("sentByUserId");
        boolean accepted = doc.getBoolean("accepted", false);

        Instant createdAt = Instant.ofEpochMilli(doc.getLong("createdAt"));
        Instant expiresAt = Instant.ofEpochMilli(doc.getLong("expiresAt"));

        return new Invitation(id, businessId, email, role, sentByUserId,
            createdAt, expiresAt, accepted);
    }

    // Implementation methods
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
    @CircuitBreakerName("mongo-invitation-save")
    public Uni<Void> saveInvitation(Invitation invitation) {
        LOG.infof("MongoDB: Saving invitation with ID: %s", invitation.getId());

        if (invitation == null) {
            return Uni.createFrom().failure(new AppException("Invitation cannot be null",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        return Uni.createFrom().item(() -> {
            try {
                // Check if we're updating an existing invitation to accepted=true
                boolean updatingToAcceptedState = invitation.isAccepted();
                if (updatingToAcceptedState) {
                    LOG.infof("MongoDB: Updating invitation to accepted=true for ID: %s",
                        invitation.getId());
                }

                Document doc = invitationToDocument(invitation);

                // First check if invitation exists
                Document existing = getInvitationsCollection().find(Filters.eq("id", invitation.getId())).first();
                if (existing != null) {
                    LOG.infof("MongoDB: Found existing invitation with ID %s, current accepted status: %s",
                        invitation.getId(), existing.getBoolean("accepted", false));
                }

                // Replace or insert
                getInvitationsCollection().replaceOne(
                    Filters.eq("id", invitation.getId()),
                    doc,
                    new ReplaceOptions().upsert(true)
                );

                // Verify the update
                Document saved = getInvitationsCollection().find(Filters.eq("id", invitation.getId())).first();
                if (saved != null) {
                    boolean savedAcceptedStatus = saved.getBoolean("accepted", false);
                    LOG.infof("MongoDB: Verified invitation saved. ID: %s, accepted: %s",
                        saved.getString("id"), savedAcceptedStatus);
                } else {
                    LOG.warnf("MongoDB: Failed to verify invitation save. Not found after save operation.");
                }

                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to save invitation with ID %s: %s", invitation.getId(), e.getMessage());
                throw new AppException("Failed to save invitation", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("mongo-invitation-get")
    public Uni<Optional<Invitation>> getInvitation(String id) {
        LOG.infof("MongoDB: Retrieving invitation with ID: %s", id);

        return Uni.createFrom().item(() -> {
            try {
                Document doc = getInvitationsCollection().find(Filters.eq("id", id)).first();
                Invitation invitation = documentToInvitation(doc);
                LOG.debugf("Retrieved invitation with ID: %s, exists: %s", id, invitation != null);
                return Optional.ofNullable(invitation);
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve invitation with ID %s: %s", id, e.getMessage());
                throw new AppException("Failed to retrieve invitation", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("mongo-invitation-list-business")
    public Uni<List<Invitation>> getInvitationsByBusiness(String businessId) {
        LOG.infof("MongoDB: Retrieving invitations for business: %s", businessId);

        return Uni.createFrom().item(() -> {
            try {
                List<Invitation> invitations = new ArrayList<>();

                FindIterable<Document> docs = getInvitationsCollection().find(
                    Filters.eq("businessId", businessId)
                ).sort(new Document("createdAt", -1)); // Newest first

                for (Document doc : docs) {
                    Invitation invitation = documentToInvitation(doc);
                    if (invitation != null) {
                        invitations.add(invitation);
                    }
                }

                LOG.infof("Found %d invitations for business: %s", invitations.size(), businessId);
                return invitations;
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve invitations for business %s: %s", businessId, e.getMessage());
                throw new AppException("Failed to retrieve invitations by business",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("mongo-invitation-list-email")
    public Uni<List<Invitation>> getInvitationsByEmail(String email) {
        LOG.infof("MongoDB: Retrieving invitations for email: %s", email);

        return Uni.createFrom().item(() -> {
            try {
                List<Invitation> invitations = new ArrayList<>();

                FindIterable<Document> docs = getInvitationsCollection().find(
                    Filters.eq("email", email)
                ).sort(new Document("createdAt", -1)); // Newest first

                for (Document doc : docs) {
                    Invitation invitation = documentToInvitation(doc);
                    if (invitation != null) {
                        invitations.add(invitation);
                    }
                }

                LOG.infof("Found %d invitations for email: %s", invitations.size(), email);
                return invitations;
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve invitations for email %s: %s", email, e.getMessage());
                throw new AppException("Failed to retrieve invitations by email",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("mongo-invitation-list-sender")
    public Uni<List<Invitation>> getInvitationsBySender(String userId) {
        LOG.infof("MongoDB: Retrieving invitations sent by user: %s", userId);

        return Uni.createFrom().item(() -> {
            try {
                List<Invitation> invitations = new ArrayList<>();

                FindIterable<Document> docs = getInvitationsCollection().find(
                    Filters.eq("sentByUserId", userId)
                ).sort(new Document("createdAt", -1)); // Newest first

                for (Document doc : docs) {
                    Invitation invitation = documentToInvitation(doc);
                    if (invitation != null) {
                        invitations.add(invitation);
                    }
                }

                LOG.infof("Found %d invitations sent by user: %s", invitations.size(), userId);
                return invitations;
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve invitations sent by user %s: %s", userId, e.getMessage());
                throw new AppException("Failed to retrieve invitations by sender",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("mongo-invitation-list-pending")
    public Uni<List<Invitation>> getPendingInvitations(String businessId) {
        LOG.infof("MongoDB: Retrieving pending invitations for business: %s", businessId);

        return Uni.createFrom().item(() -> {
            try {
                List<Invitation> invitations = new ArrayList<>();
                Instant now = Instant.now();

                // Find invitations that are:
                // 1. For the specified business
                // 2. Not accepted
                // 3. Not expired
                FindIterable<Document> docs = getInvitationsCollection().find(
                    Filters.and(
                        Filters.eq("businessId", businessId),
                        Filters.eq("accepted", false),
                        Filters.gt("expiresAt", now.toEpochMilli())
                    )
                ).sort(new Document("createdAt", -1)); // Newest first

                for (Document doc : docs) {
                    Invitation invitation = documentToInvitation(doc);
                    if (invitation != null) {
                        invitations.add(invitation);
                    }
                }

                LOG.infof("Found %d pending invitations for business: %s", invitations.size(), businessId);
                return invitations;
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve pending invitations for business %s: %s", businessId, e.getMessage());
                throw new AppException("Failed to retrieve pending invitations",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("mongo-invitation-delete")
    public Uni<Void> deleteInvitation(String id) {
        LOG.infof("MongoDB: Deleting invitation with ID: %s", id);

        return Uni.createFrom().item(() -> {
            try {
                getInvitationsCollection().deleteOne(Filters.eq("id", id));
                LOG.debugf("Successfully deleted invitation with ID: %s", id);
                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to delete invitation with ID %s: %s", id, e.getMessage());
                throw new AppException("Failed to delete invitation", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5)
    @CircuitBreakerName("mongo-user-is-admin")
    public Uni<Boolean> isUserAdmin(String userId, String businessId) {
        LOG.infof("MongoDB: Checking if user %s is admin for business %s", userId, businessId);

        return Uni.createFrom().item(() -> {
            try {
                // Query for business_user with the given userId and businessId
                Bson filter = Filters.and(
                    Filters.eq("id", userId),
                    Filters.eq("businessId", businessId),
                    Filters.eq("role", "admin")
                );

                Document businessUser = getBusinessUsersCollection().find(filter).first();
                boolean isAdmin = (businessUser != null);

                LOG.infof("User %s is admin for business %s: %s", userId, businessId, isAdmin);
                return isAdmin;
            } catch (Exception e) {
                LOG.errorf("Failed to check if user %s is admin: %s", userId, e.getMessage());
                throw new AppException("Failed to check admin status", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }
}
