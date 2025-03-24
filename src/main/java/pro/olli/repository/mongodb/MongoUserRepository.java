/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.repository.mongodb;

import com.google.gson.Gson;
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
import java.util.ArrayList;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.Business;
import pro.olli.models.BusinessUser;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.User;
import pro.olli.models.Verification;
import pro.olli.repository.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MongoDB implementation of the UserRepository interface.
 */
@ApplicationScoped
@Named("mongodb-user")
public class MongoUserRepository implements UserRepository {

    private static final Logger LOG = Logger.getLogger(MongoUserRepository.class);
    private static final String USERS_COLLECTION = "users";
    private static final String BUSINESSES_COLLECTION = "businesses";
    private static final String VERIFICATIONS_COLLECTION = "verifications";
    private static final String BUSINESS_USERS_COLLECTION = "business_users";

    private final MongoClient mongoClient;
    private final Gson gson;
    private final String databaseName;

    @Inject
    public MongoUserRepository(
            MongoClient mongoClient,
            Gson gson,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli") String databaseName) {
        this.mongoClient = mongoClient;
        this.gson = gson;
        this.databaseName = databaseName;
        LOG.infof("MongoUserRepository initialized with database: %s", databaseName);
    }

    // Helper methods to get the collections
    private MongoCollection<Document> getUsersCollection() {
        return mongoClient.getDatabase(databaseName).getCollection(USERS_COLLECTION);
    }


    private MongoCollection<Document> getVerificationsCollection() {
        return mongoClient.getDatabase(databaseName).getCollection(VERIFICATIONS_COLLECTION);
    }

    private MongoCollection<Document> getBusinessesCollection() {
        return mongoClient.getDatabase(databaseName).getCollection(BUSINESSES_COLLECTION);
    }

    private MongoCollection<Document> getBusinessUsersCollection() {
        return mongoClient.getDatabase(databaseName).getCollection(BUSINESS_USERS_COLLECTION);
    }

    // Document mapping methods
    private Document userToDocument(User user) {
        Document doc = new Document();
        doc.append("id", user.getId());
        doc.append("email", user.getEmail());

        if (user.getName() != null) {
            doc.append("name", user.getName());
        }

        if (user.getAvatarUrl() != null) {
            doc.append("avatarUrl", user.getAvatarUrl());
        }

        doc.append("createdAt", user.getCreatedAt().toEpochMilli());
        doc.append("updatedAt", user.getUpdatedAt().toEpochMilli());

        if (user.getMetadata() != null) {
            doc.append("metadata", user.getMetadata());
        }

        return doc;
    }

    private User documentToUser(Document doc) {
        if (doc == null) {
            return null;
        }

        String id = doc.getString("id");
        String email = doc.getString("email");
        String name = doc.getString("name");
        String avatarUrl = doc.getString("avatarUrl");

        Instant createdAt = Instant.ofEpochMilli(doc.getLong("createdAt"));
        Instant updatedAt = Instant.ofEpochMilli(doc.getLong("updatedAt"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");

        return new User(id, email, name, avatarUrl, createdAt, updatedAt, metadata, null);
    }


    // Helper methods for Verification document conversion
    private Document verificationToDocument(Verification verification) {
        LOG.debugf("Converting Verification to Document: token=%s, email=%s, verified=%s",
            verification.getToken(), verification.getEmail(), verification.isVerified());

        Document doc = new Document();
        doc.append("token", verification.getToken());
        doc.append("businessId", verification.getBusinessId());
        doc.append("email", verification.getEmail());

        if (verification.getRole() != null) {
            doc.append("role", verification.getRole());
        }

        doc.append("createdAt", verification.getCreatedAt().toEpochMilli());
        doc.append("expiresAt", verification.getExpiresAt().toEpochMilli());
        doc.append("verified", verification.isVerified());

        LOG.debugf("Verification document created with verified=%s", verification.isVerified());

        return doc;
    }

    private Verification documentToVerification(Document doc) {
        if (doc == null) {
            return null;
        }

        String token = doc.getString("token");
        String businessId = doc.getString("businessId");
        String email = doc.getString("email");
        String role = doc.getString("role");
        boolean verified = doc.getBoolean("verified", false);

        Instant createdAt = Instant.ofEpochMilli(doc.getLong("createdAt"));
        Instant expiresAt = Instant.ofEpochMilli(doc.getLong("expiresAt"));

        LOG.debugf("Converting Document to Verification: token=%s, email=%s, verified=%s",
            token, email, verified);

        Verification verification = new Verification(token, businessId, email, role,
                                   createdAt, expiresAt, verified);

        LOG.debugf("Created Verification object with verified=%s", verification.isVerified());

        return verification;
    }

    // Interface implementation methods
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
    @CircuitBreakerName("mongo-user-save")
    public Uni<Void> saveUser(User user) {
        LOG.infof("MongoDB saveUser called with user: id=%s, email=%s", user.getId(), user.getEmail());
        if (user == null) {
            return Uni.createFrom().failure(new AppException("User cannot be null",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return Uni.createFrom().failure(new AppException("User email cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        LOG.infof("MongoDB: Saving user with email: %s", user.getEmail());

        return Uni.createFrom().item(() -> {
            try {
                // Debug print to show what we're trying to save
                LOG.infof("Converting user to document: email=%s, metadata=%s",
                         user.getEmail(), user.getMetadata());

                Document doc = userToDocument(user);
                LOG.infof("Document for save: %s", doc.toJson());

                // Look for the user first
                Document existingUser = getUsersCollection().find(Filters.eq("email", user.getEmail())).first();
                if (existingUser != null) {
                    LOG.infof("Found existing user with email %s", user.getEmail());
                }

                LOG.infof("Starting MongoDB replaceOne operation for user with ID: %s, email: %s", user.getId(), user.getEmail());
                // Replace the document (or insert if it doesn't exist)
                getUsersCollection().replaceOne(
                    Filters.eq("id", user.getId()),  // Use ID instead of email as the primary key
                    doc,
                    new ReplaceOptions().upsert(true)
                );
                LOG.infof("Completed MongoDB replaceOne operation for user");

                // Verify the save worked by retrieving the user
                Document savedUser = getUsersCollection().find(Filters.eq("id", user.getId())).first();
                if (savedUser != null) {
                    LOG.infof("Verified user was saved: %s", savedUser.toJson());
                } else {
                    LOG.warnf("User save verification failed - user not found after save");
                    // Let's try by email too
                    Document savedByEmail = getUsersCollection().find(Filters.eq("email", user.getEmail())).first();
                    if (savedByEmail != null) {
                        LOG.infof("Found user by email instead: %s", savedByEmail.toJson());
                    } else {
                        LOG.errorf("CRITICAL: User was not saved to database at all!");
                    }
                }

                LOG.debugf("Successfully saved user with email: %s", user.getEmail());
                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to save user with email %s: %s", user.getEmail(), e.getMessage());
                throw new AppException("Failed to save user", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
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
    @CircuitBreakerName("mongo-user-get")
    public Uni<Optional<User>> getUser(String emailOrId) {
        if (emailOrId == null || emailOrId.isBlank()) {
            return Uni.createFrom().failure(new AppException("Email or ID cannot be null or empty",
                AppException.ErrorType.VALIDATION_ERROR));
        }

        LOG.infof("MongoDB: Retrieving user with email/ID: %s", emailOrId);

        return Uni.createFrom().item(() -> {
            try {
                // Create a query that can match either email or id
                Bson filter = Filters.or(
                    Filters.eq("email", emailOrId),
                    Filters.eq("id", emailOrId)
                );

                Document doc = getUsersCollection().find(filter).first();

                if (doc == null) {
                    LOG.debugf("No user found with email/ID: %s", emailOrId);
                    return Optional.empty();
                }

                User user = documentToUser(doc);
                LOG.infof("Retrieved user: id=%s, email=%s", user.getId(), user.getEmail());
                return Optional.ofNullable(user);
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve user with email/ID %s: %s", emailOrId, e.getMessage());
                throw new AppException("Failed to retrieve user", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }


    @Override
    public Uni<Void> saveVerification(Verification verification) {
        LOG.infof("MongoDB: Saving verification with token: %s, verified: %s",
            verification.getToken(), verification.isVerified());
        return Uni.createFrom().item(() -> {
            try {
                // Check if we're updating an existing verification to verified=true
                boolean updatingToVerifiedState = verification.isVerified();
                if (updatingToVerifiedState) {
                    LOG.infof("MongoDB: Attempting to update verification to verified=true for token: %s",
                        verification.getToken());
                }

                Document doc = verificationToDocument(verification);
                LOG.infof("MongoDB: Document to save: %s", doc.toJson());

                // First check if verification exists
                Document existing = getVerificationsCollection().find(Filters.eq("token", verification.getToken())).first();
                if (existing != null) {
                    LOG.infof("MongoDB: Found existing verification with token %s, current verified status: %s",
                        verification.getToken(), existing.getBoolean("verified", false));

                    // Extra logging for verified status updates
                    if (updatingToVerifiedState && !existing.getBoolean("verified", false)) {
                        LOG.infof("MongoDB: Changing verification from unverified to verified for token: %s",
                            verification.getToken());
                    }
                }

                // Now replace or insert
                LOG.infof("MongoDB: Executing replaceOne operation with token: %s, verified=%s",
                    verification.getToken(), verification.isVerified());
                getVerificationsCollection().replaceOne(
                    Filters.eq("token", verification.getToken()),
                    doc,
                    new ReplaceOptions().upsert(true)
                );

                // Verify the update worked with explicit checks for verified status
                Document saved = getVerificationsCollection().find(Filters.eq("token", verification.getToken())).first();
                if (saved != null) {
                    boolean savedVerifiedStatus = saved.getBoolean("verified", false);
                    LOG.infof("MongoDB: Verified verification saved. Token: %s, verified: %s",
                        saved.getString("token"), savedVerifiedStatus);

                    // Double-check the verified status was saved correctly
                    if (updatingToVerifiedState && !savedVerifiedStatus) {
                        LOG.errorf("MongoDB: CRITICAL ERROR - Failed to update verified status! Expected verified=true but got verified=false");
                    } else if (updatingToVerifiedState) {
                        LOG.infof("MongoDB: Successfully verified verified=true was saved correctly");
                    }
                } else {
                    LOG.warnf("MongoDB: Failed to verify verification save. Verification not found after save operation.");
                }

                LOG.debugf("Successfully saved verification with token: %s", verification.getToken());
                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to save verification with token %s: %s", verification.getToken(), e.getMessage());
                throw new AppException("Failed to save verification", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Optional<Verification>> getVerification(String token) {
        LOG.infof("MongoDB: Retrieving verification with token: %s", token);
        return Uni.createFrom().item(() -> {
            try {
                Document doc = getVerificationsCollection().find(Filters.eq("token", token)).first();
                Verification verification = documentToVerification(doc);
                LOG.debugf("Retrieved verification with token: %s, exists: %s", token, verification != null);
                return Optional.ofNullable(verification);
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve verification with token %s: %s", token, e.getMessage());
                throw new AppException("Failed to retrieve verification", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Void> deleteVerification(String token) {
        LOG.infof("MongoDB: Deleting verification with token: %s", token);
        return Uni.createFrom().item(() -> {
            try {
                getVerificationsCollection().deleteOne(Filters.eq("token", token));
                LOG.debugf("Successfully deleted verification with token: %s", token);
                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to delete verification with token %s: %s", token, e.getMessage());
                throw new AppException("Failed to delete verification", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Optional<Verification>> getVerificationByEmail(String email) {
        LOG.infof("MongoDB: Retrieving verification for email: %s", email);
        return Uni.createFrom().item(() -> {
            try {
                // Find the most recent verification for this email
                FindIterable<Document> verifications = getVerificationsCollection()
                    .find(Filters.eq("email", email))
                    .sort(new Document("createdAt", -1)) // Sort by createdAt descending (newest first)
                    .limit(1); // Only get the most recent one

                Document doc = verifications.first();
                if (doc == null) {
                    LOG.debugf("No verification found for email: %s", email);
                    return Optional.empty();
                }

                Verification verification = documentToVerification(doc);
                LOG.infof("Retrieved verification for email: %s, token: %s, verified: %s",
                    email, verification.getToken(), verification.isVerified());

                return Optional.of(verification);
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve verification for email %s: %s", email, e.getMessage());
                throw new AppException("Failed to retrieve verification by email",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Optional<Business>> getBusiness(String businessId) {
        LOG.infof("MongoDB: Retrieving business with ID: %s", businessId);
        return Uni.createFrom().item(() -> {
            try {
                Document doc = getBusinessesCollection().find(Filters.eq("id", businessId)).first();
                if (doc == null) {
                    LOG.debugf("Business with ID %s not found", businessId);
                    return Optional.empty();
                }

                String id = doc.getString("id");
                String name = doc.getString("name");
                String plan = doc.getString("plan");

                // Create a business with the basic information we have
                Business business = new Business(id, name, plan);
                LOG.debugf("Retrieved business: %s", business);
                return Optional.of(business);
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve business with ID %s: %s", businessId, e.getMessage());
                throw new AppException("Failed to retrieve business", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Void> saveBusiness(Business business) {
        LOG.infof("MongoDB: Saving business with ID: %s", business.getId());
        return Uni.createFrom().item(() -> {
            try {
                Document doc = new Document()
                    .append("id", business.getId())
                    .append("name", business.getName())
                    .append("plan", business.getSubscriptionPlan());

                getBusinessesCollection().replaceOne(
                    Filters.eq("id", business.getId()),
                    doc,
                    new ReplaceOptions().upsert(true)
                );

                LOG.debugf("Successfully saved business with ID: %s", business.getId());
                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to save business with ID %s: %s", business.getId(), e.getMessage());
                throw new AppException("Failed to save business", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Void> saveBusinessUser(BusinessUser businessUser) {
        LOG.infof("MongoDB: Saving business user with business ID: %s, user ID: %s",
                 businessUser.getBusinessId(), businessUser.getId());
        return Uni.createFrom().item(() -> {
            try {
                Document doc = new Document()
                    .append("id", businessUser.getId())
                    .append("businessId", businessUser.getBusinessId())
                    .append("email", businessUser.getEmail())
                    .append("role", businessUser.getRole());

                if (businessUser.getName() != null) {
                    doc.append("name", businessUser.getName());
                }

                if (businessUser.getSyncHistory() != null) {
                    doc.append("syncHistory", businessUser.getSyncHistory());
                }

                LOG.debugf("Saving business user document: %s", doc.toJson());

                getBusinessUsersCollection().replaceOne(
                    Filters.and(
                        Filters.eq("businessId", businessUser.getBusinessId()),
                        Filters.eq("id", businessUser.getId())
                    ),
                    doc,
                    new ReplaceOptions().upsert(true)
                );

                // Verify the save
                Document saved = getBusinessUsersCollection().find(Filters.and(
                    Filters.eq("businessId", businessUser.getBusinessId()),
                    Filters.eq("id", businessUser.getId())
                )).first();

                if (saved != null) {
                    LOG.infof("Successfully saved business user: %s", saved.toJson());
                } else {
                    LOG.warnf("Business user save verification failed - record not found after save");
                }

                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to save business user: %s", e.getMessage());
                throw new AppException("Failed to save business user", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Optional<BusinessUser>> getBusinessUser(String businessId, String userId) {
        LOG.infof("MongoDB: Retrieving business user with business ID: %s, user ID: %s", businessId, userId);
        return Uni.createFrom().item(() -> {
            try {
                Document doc = getBusinessUsersCollection().find(Filters.and(
                    Filters.eq("businessId", businessId),
                    Filters.eq("id", userId)
                )).first();

                if (doc == null) {
                    LOG.debugf("Business user not found for business ID: %s, user ID: %s", businessId, userId);
                    return Optional.empty();
                }

                String id = doc.getString("id");
                String email = doc.getString("email");
                String name = doc.getString("name");
                String role = doc.getString("role");

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> syncHistory = (List<Map<String, Object>>) doc.get("syncHistory");

                // Convert the Map-based syncHistory to SyncHistoryEntry objects if needed
                List<SyncHistoryEntry> syncHistoryEntries = null;

                BusinessUser businessUser = new BusinessUser(id, businessId, email, name, role,
                                                        Instant.now(), Instant.now(),
                                                        syncHistoryEntries, null);

                LOG.debugf("Retrieved business user: %s", businessUser);
                return Optional.of(businessUser);
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve business user: %s", e.getMessage());
                throw new AppException("Failed to retrieve business user", e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Boolean> isUserInBusiness(String userId, String businessId) {
        LOG.infof("MongoDB: Checking if user %s is in business %s", userId, businessId);
        return Uni.createFrom().item(() -> {
            try {
                Document doc = getBusinessUsersCollection().find(Filters.and(
                    Filters.eq("businessId", businessId),
                    Filters.eq("id", userId)
                )).first();

                return doc != null;
            } catch (Exception e) {
                LOG.errorf("Failed to check if user is in business: %s", e.getMessage());
                throw new AppException("Failed to check if user is in business",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Boolean> isUserAdmin(String userId, String businessId) {
        LOG.infof("MongoDB: Checking if user %s is admin for business %s", userId, businessId);
        return Uni.createFrom().item(() -> {
            try {
                Document doc = getBusinessUsersCollection().find(Filters.and(
                    Filters.eq("businessId", businessId),
                    Filters.eq("id", userId),
                    Filters.eq("role", "admin")
                )).first();

                return doc != null;
            } catch (Exception e) {
                LOG.errorf("Failed to check if user is admin: %s", e.getMessage());
                throw new AppException("Failed to check if user is admin",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Optional<User>> getUserByEmail(String email) {
        LOG.infof("MongoDB: Retrieving user with email: %s", email);
        return Uni.createFrom().item(() -> {
            try {
                Document doc = getUsersCollection().find(Filters.eq("email", email)).first();

                if (doc == null) {
                    LOG.debugf("No user found with email: %s", email);
                    return Optional.empty();
                }

                User user = documentToUser(doc);
                LOG.infof("Retrieved user by email: id=%s, email=%s", user.getId(), user.getEmail());
                return Optional.of(user);
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve user with email %s: %s", email, e.getMessage());
                throw new AppException("Failed to retrieve user by email",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<List<Business>> getBusinessesDueForBilling(Instant dueDate) {
        LOG.infof("MongoDB: Retrieving businesses due for billing on or before: %s", dueDate);
        return Uni.createFrom().item(() -> {
            try {
                // Find businesses with nextBillingDate on or before the dueDate
                List<Business> businesses = new ArrayList<>();

                // This is a placeholder implementation
                // In a real implementation, you would query MongoDB for businesses with nextBillingDate <= dueDate

                return businesses;
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve businesses due for billing: %s", e.getMessage());
                throw new AppException("Failed to retrieve businesses due for billing",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }
    
    @Override
    public Uni<Optional<Business>> getBusinessBySubscriptionId(String subscriptionId) {
        LOG.infof("MongoDB: Looking up business by subscription ID: %s", subscriptionId);
        return Uni.createFrom().item(() -> {
            try {
                // Create a query for the stripeSubscriptionId in metadata
                Document query = new Document("metadata.stripeSubscriptionId", subscriptionId);
                Document doc = getBusinessesCollection().find(query).first();
                
                if (doc == null) {
                    LOG.debugf("No business found with subscription ID: %s", subscriptionId);
                    return Optional.empty();
                }
                
                // Extract business data from the document
                String id = doc.getString("id");
                String name = doc.getString("name");
                String plan = doc.getString("plan");
                String status = doc.getString("status");
                
                // Handle timestamps
                Instant subscriptionStartDate = null;
                Object subscriptionStartObj = doc.get("subscriptionStartDate");
                if (subscriptionStartObj instanceof Long) {
                    subscriptionStartDate = Instant.ofEpochMilli((Long) subscriptionStartObj);
                }
                
                Instant nextBillingDate = null;
                Object nextBillingObj = doc.get("nextBillingDate");
                if (nextBillingObj instanceof Long) {
                    nextBillingDate = Instant.ofEpochMilli((Long) nextBillingObj);
                }
                
                String paymentMethodId = doc.getString("paymentMethodId");
                
                Instant createdAt = null;
                Object createdAtObj = doc.get("createdAt");
                if (createdAtObj instanceof Long) {
                    createdAt = Instant.ofEpochMilli((Long) createdAtObj);
                }
                
                Instant updatedAt = null;
                Object updatedAtObj = doc.get("updatedAt");
                if (updatedAtObj instanceof Long) {
                    updatedAt = Instant.ofEpochMilli((Long) updatedAtObj);
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> settings = (Map<String, Object>) doc.get("settings");
                
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
                
                // Create the business object with available data
                Business business = new Business(
                        id,
                        name,
                        plan,
                        status,
                        subscriptionStartDate != null ? subscriptionStartDate : Instant.now(),
                        nextBillingDate != null ? nextBillingDate : Instant.now().plusSeconds(30 * 24 * 60 * 60),
                        paymentMethodId,
                        createdAt != null ? createdAt : Instant.now(),
                        updatedAt != null ? updatedAt : Instant.now(),
                        settings,
                        metadata,
                        0L, // currentConnectorUsageBytes
                        0L, // documentsProcessedThisMonth
                        0L // queriesThisMonth
                );
                
                LOG.infof("MongoDB: Found business %s for subscription ID: %s", id, subscriptionId);
                return Optional.of(business);
                
            } catch (Exception e) {
                LOG.errorf("Failed to retrieve business by subscription ID %s: %s", subscriptionId, e.getMessage());
                throw new AppException("Failed to retrieve business by subscription ID",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }

    @Override
    public Uni<Void> updateBusinessUserSyncHistory(String businessId, String userId, BusinessUser updatedUser) {
        LOG.infof("MongoDB: Updating business user sync history for business ID: %s, user ID: %s", businessId, userId);
        return Uni.createFrom().item(() -> {
            try {
                // Get the existing business user
                Document existingDoc = getBusinessUsersCollection().find(Filters.and(
                    Filters.eq("businessId", businessId),
                    Filters.eq("id", userId)
                )).first();

                if (existingDoc == null) {
                    throw new AppException("Business user not found", AppException.ErrorType.RESOURCE_ERROR);
                }

                // Update only the sync history
                // Convert SyncHistoryEntry objects to a format MongoDB can store if needed
                Bson updates = new Document("$set",
                    new Document("syncHistory", updatedUser.getSyncHistory())
                );

                getBusinessUsersCollection().updateOne(
                    Filters.and(
                        Filters.eq("businessId", businessId),
                        Filters.eq("id", userId)
                    ),
                    updates
                );

                LOG.debugf("Successfully updated sync history for business user: business ID: %s, user ID: %s",
                    businessId, userId);
                return null;
            } catch (Exception e) {
                LOG.errorf("Failed to update business user sync history: %s", e.getMessage());
                throw new AppException("Failed to update business user sync history",
                    e, AppException.ErrorType.DATA_ACCESS_ERROR);
            }
        });
    }
}
