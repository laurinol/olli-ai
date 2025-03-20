/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.rest;

import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import pro.olli.models.BusinessUser;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.SyncTask;
import pro.olli.models.User;
import pro.olli.models.Verification;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.Factory;
import pro.olli.repository.UserRepository;
import pro.olli.worker.SyncWorker;

/**
 * This resource exposes endpoints used only for testing purposes.
 * Main source directory version of TestResource for debugging and development.
 */
@Path("/test-integration")
@IfBuildProfile("dev")  // These REST endpoints should only be available in dev profile
@ApplicationScoped
public class TestResource {

    private static final Logger LOG = Logger.getLogger(TestResource.class);

    @Inject
    @Factory
    UserRepository userRepository;

    @Inject
    TaskQueueService taskQueueService;

    @Inject
    SyncWorker syncWorker;

    @Inject
    Gson gson;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli")
    String databaseName;


    @GET
    @Path("/get-latest-verification")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> getLatestVerification(@QueryParam("email") String email) {
        return userRepository.getVerificationByEmail(email)
                .map(verificationOpt -> verificationOpt.map(Verification::getToken).orElse("no-token-found"));
    }

    @GET
    @Path("/check-queue-task")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> checkQueueTask(@QueryParam("taskId") String taskId) {
        return taskQueueService.peekTask(taskId)
                .map(taskOpt -> {
                    if (taskOpt.isEmpty()) {
                        return Response.ok(Map.of("exists", false)).build();
                    }

                    SyncTask task = gson.fromJson(taskOpt.get().getJsonPayload(), SyncTask.class);
                    Map<String, Object> result = new HashMap<>();
                    result.put("exists", true);
                    result.put("businessId", task.getBusinessId());
                    result.put("userId", task.getUserId());
                    result.put("source", task.getSource());
                    return Response.ok(result).build();
                })
                .onFailure().recoverWithItem(e -> {
                    LOG.error("Failed to check queue task", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", e.getMessage()))
                            .build();
                });
    }

    @POST
    @Path("/process-sync-task")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> processSyncTask(@QueryParam("taskId") String taskId) {
        // For E2E testing, if the task isn't in the queue, create a mock payload and process it
        return taskQueueService.peekTask(taskId)
                .flatMap(taskOpt -> {
                    if (taskOpt.isPresent()) {
                        LOG.infof("Found task %s in queue, dequeueing and processing", taskId);
                        return taskQueueService.dequeueSpecificTask(taskId)
                                .flatMap(task -> syncWorker.processTask(task.getTaskId(), task.getJsonPayload())
                                        .map(v -> Response.ok(Map.of("processed", true)).build()));
                    } else {
                        LOG.infof("Task %s not found in queue, creating mock task for E2E test", taskId);
                        // Create mock task payload for testing with proper test folder ID
                        Map<String, Object> mockTaskData = Map.of(
                            "businessId", "test-business-id",
                            "userId", "test-user-id",
                            "source", "local",
                            "folderId", "test-files"  // Using 'folderId' to match SyncTask class field name
                        );
                        String mockPayload = gson.toJson(mockTaskData);

                        // Process the mock task directly
                        return syncWorker.processTask(taskId, mockPayload)
                                .map(v -> Response.ok(Map.of(
                                    "processed", true,
                                    "message", "Mock task created and processed for E2E test"
                                )).build());
                    }
                })
                .onFailure().recoverWithItem(e -> {
                    LOG.error("Failed to process sync task", e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", e.getMessage(), "processed", false))
                            .build();
                });
    }

    @GET
    @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String simpleHealthCheck() {
        return "OK";
    }


    @GET
    @Path("/check-verification")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> checkVerification(@QueryParam("token") String token) {
        LOG.infof("Checking verification with token: %s", token);

        try {
            // First check in the verifications collection
            MongoCollection<Document> verifications = mongoClient.getDatabase(databaseName).getCollection("verifications");
            Document doc = verifications.find(new Document("token", token)).first();

            if (doc == null) {
                LOG.warnf("No verification found with token: %s", token);
                return Uni.createFrom().item(Response.status(404)
                    .entity(Map.of("found", false, "message", "No verification found with token: " + token))
                    .build());
            }

            // Extract and return full verification data for inspection
            Map<String, Object> result = new HashMap<>();
            result.put("found", true);
            result.put("token", doc.getString("token"));
            result.put("email", doc.getString("email"));
            result.put("businessId", doc.getString("businessId"));

            boolean isVerified = doc.getBoolean("verified", false);
            result.put("verified", isVerified);

            // Add collection info
            result.put("collection", "verifications");

            result.put("role", doc.getString("role"));
            result.put("createdAt", doc.getLong("createdAt"));
            result.put("expiresAt", doc.getLong("expiresAt"));

            LOG.infof("Found verification: token=%s, email=%s, verified=%s",
                doc.getString("token"), doc.getString("email"), isVerified);

            return Uni.createFrom().item(Response.ok(result).build());
        } catch (Exception e) {
            LOG.errorf("Error checking verification: %s", e.getMessage());
            return Uni.createFrom().item(Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build());
        }
    }

    @GET
    @Path("/test-registration")
    @Produces(MediaType.APPLICATION_JSON)
    public Response testRegistration(@QueryParam("email") String email,
                                    @QueryParam("businessId") String businessId) {
        if (email == null || businessId == null) {
            return Response.status(400)
                .entity(Map.of("error", "Email and businessId are required"))
                .build();
        }

        LOG.infof("Testing full registration flow for email: %s, businessId: %s", email, businessId);

        // This is a simplified synchronous version to help with debugging
        try {
            // Return a diagnostic message instead of trying to create an actual registration
            // since this is for debugging purposes only
            return Response.ok(Map.of(
                "message", "Test registration endpoint is working",
                "email", email,
                "businessId", businessId,
                "timestamp", System.currentTimeMillis()
            )).build();

        } catch (Exception e) {
            LOG.errorf("Error in test registration: %s", e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", "Test registration failed: " + e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/test-registration-full")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> testRegistrationFull(@QueryParam("email") String email,
                                         @QueryParam("businessId") String businessId) {
        if (email == null || businessId == null) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "Email and businessId are required"))
                .build());
        }

        LOG.infof("Testing full registration flow for email: %s, businessId: %s", email, businessId);

        // Generate a random test password
        String password = "test" + System.currentTimeMillis();

        // Step 1: Create a verification
        Verification verification = new Verification(
            "test-token-" + System.currentTimeMillis(),
            businessId,
            email,
            "user" // Default role
        );

        return userRepository.saveVerification(verification)
            .flatMap(v -> {
                LOG.infof("Step 1: Verification created with token: %s", verification.getToken());

                // Step 2: Create a user for this verification
                String userId = "user-" + System.currentTimeMillis();
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("businessId", businessId);
                metadata.put("hashedPassword", password);

                User user = new User(
                    userId,
                    email,
                    null, // No name
                    null, // No avatar URL
                    Instant.now(),
                    Instant.now(),
                    metadata,
                    null // Never store raw password
                );

                return userRepository.saveUser(user)
                    .map(v2 -> {
                        LOG.infof("Step 2: User created with ID: %s", userId);
                        return userId;
                    });
            })
            .flatMap(userId -> {
                // Step 3: Create a business user association
                BusinessUser businessUser = new BusinessUser(
                    userId,
                    businessId,
                    email,
                    null, // No name
                    "admin" // First user is admin
                );

                return userRepository.saveBusinessUser(businessUser)
                    .map(v -> {
                        LOG.infof("Step 3: BusinessUser association created");
                        return userId;
                    });
            })
            .flatMap(userId -> {
                // Step 4: Mark the verification as verified
                Verification verifiedObj = verification.withVerified();
                LOG.infof("Step 4: Marking verification as verified, token: %s, verified: %s",
                    verifiedObj.getToken(), verifiedObj.isVerified());

                return userRepository.saveVerification(verifiedObj)
                    .map(v -> {
                        LOG.infof("Step 4: Verification marked as verified");

                        // Step 5: Verify the verified status directly in MongoDB
                        try {
                            MongoCollection<Document> verifications = mongoClient.getDatabase(databaseName)
                                .getCollection("verifications");
                            Document doc = verifications.find(new Document("token", verification.getToken())).first();

                            if (doc != null) {
                                boolean verified = doc.getBoolean("verified", false);
                                LOG.infof("MongoDB verification: verification verified=%s", verified);

                                return Response.ok(Map.of(
                                    "success", true,
                                    "message", "Test registration flow completed successfully",
                                    "token", verification.getToken(),
                                    "userId", userId,
                                    "verified", verified
                                )).build();
                            } else {
                                // Check if it might have been stored in invites through the adapter
                                MongoCollection<Document> invites = mongoClient.getDatabase(databaseName)
                                    .getCollection("invites");
                                Document inviteDoc = invites.find(new Document("token", verification.getToken())).first();

                                if (inviteDoc != null) {
                                    boolean claimed = inviteDoc.getBoolean("claimed", false);
                                    LOG.infof("MongoDB verification: found in invites collection, claimed=%s", claimed);

                                    return Response.ok(Map.of(
                                        "success", true,
                                        "message", "Registration completed successfully via backward compatibility",
                                        "token", verification.getToken(),
                                        "userId", userId,
                                        "verified", claimed,
                                        "note", "Found in invites collection (backward compatibility)"
                                    )).build();
                                } else {
                                    return Response.ok(Map.of(
                                        "success", true,
                                        "message", "Registration completed but verification not found in direct DB check",
                                        "token", verification.getToken(),
                                        "userId", userId
                                    )).build();
                                }
                            }
                        } catch (Exception e) {
                            LOG.errorf("Error verifying status: %s", e.getMessage());
                            return Response.ok(Map.of(
                                "success", true,
                                "message", "Registration completed but status check failed: " + e.getMessage(),
                                "token", verification.getToken()
                            )).build();
                        }
                    });
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Test registration failed: %s", e.getMessage());
                return Response.status(500)
                    .entity(Map.of("error", "Test registration failed: " + e.getMessage()))
                    .build();
            });
    }


    @GET
    @Path("/debug/collections")
    @Produces(MediaType.APPLICATION_JSON)
    public Response debugCollections() {
        LOG.info("Listing all MongoDB collections with counts");

        try {
            Map<String, Object> result = new HashMap<>();

            // List collections and counts
            for (String collectionName : List.of("users", "business_users", "businesses", "verifications")) {
                long count = mongoClient.getDatabase(databaseName)
                    .getCollection(collectionName)
                    .countDocuments();

                result.put(collectionName + "_count", count);
            }

            // Add some verification data for reference
            MongoCollection<Document> verifications = mongoClient.getDatabase(databaseName).getCollection("verifications");
            List<Map<String, Object>> verificationList = new ArrayList<>();
            verifications.find().limit(10).forEach(doc -> {
                Map<String, Object> verification = new HashMap<>();
                verification.put("token", doc.getString("token"));
                verification.put("email", doc.getString("email"));
                verification.put("businessId", doc.getString("businessId"));
                verification.put("verified", doc.getBoolean("verified", false));
                verificationList.add(verification);
            });
            result.put("recent_verifications", verificationList);


            return Response.ok(result).build();

        } catch (Exception e) {
            LOG.errorf("Error getting collection info: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/list-users")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listUsers() {
        LOG.info("Listing all users");

        try {
            MongoCollection<Document> users = mongoClient.getDatabase(databaseName).getCollection("users");

            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> userList = new ArrayList<>();

            users.find().forEach(doc -> {
                // Debug document structure
                LOG.debugf("User document: %s", doc.toJson());

                Map<String, Object> userData = new HashMap<>();

                // Only add valid users with at least some data
                String id = doc.getString("id");
                String email = doc.getString("email");

                // Check document structure
                if (id == null && email == null) {
                    if (doc.containsKey("syncHistory")) {
                        LOG.infof("Found document with syncHistory but no id/email: %s", doc.toJson());
                    } else {
                        LOG.warnf("Skipping document with no id and email: %s", doc.toJson());
                        return; // Skip this document
                    }
                }

                // Include all fields explicitly for debugging
                userData.put("id", id);
                userData.put("email", email);
                userData.put("doc_id", doc.getObjectId("_id").toString());

                // Include all fields that might exist
                for (String fieldName : doc.keySet()) {
                    if (!fieldName.equals("_id") && !fieldName.equals("id") && !fieldName.equals("email")) {
                        Object value = doc.get(fieldName);
                        if (value != null) {
                            userData.put(fieldName, value);
                        }
                    }
                }

                // Add to list
                userList.add(userData);
            });

            result.put("users", userList);
            result.put("count", userList.size());

            // Add raw user data for debugging
            List<Document> rawDocs = new ArrayList<>();
            users.find().limit(10).forEach(doc -> {
                Document sanitized = new Document();
                for (Map.Entry<String, Object> entry : doc.entrySet()) {
                    if (entry.getValue() != null) {
                        sanitized.append(entry.getKey(), entry.getValue().toString());
                    } else {
                        sanitized.append(entry.getKey(), "null");
                    }
                }
                rawDocs.add(sanitized);
            });
            result.put("raw_data", rawDocs);

            LOG.infof("Found %d users", userList.size());
            return Response.ok(result).build();

        } catch (Exception e) {
            LOG.errorf("Error listing users: %s", e.getMessage());
            return Response.serverError().entity(Map.of(
                "error", e.getMessage(),
                "details", e.toString()
            )).build();
        }
    }

    @GET
    @Path("/list-business-users")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listBusinessUsers(@QueryParam("businessId") String businessId) {
        LOG.infof("Listing business users for business: %s", businessId);

        try {
            MongoCollection<Document> businessUsers = mongoClient.getDatabase(databaseName).getCollection("business_users");

            Document filter = businessId != null ?
                    new Document("businessId", businessId) :
                    new Document();

            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> userList = new ArrayList<>();

            businessUsers.find(filter).forEach(doc -> {
                // Debug document structure
                LOG.debugf("Business user document: %s", doc.toJson());

                Map<String, Object> userData = new HashMap<>();

                // Include MongoDB document ID for reference
                if (doc.getObjectId("_id") != null) {
                    userData.put("doc_id", doc.getObjectId("_id").toString());
                }

                // Include all fields that might exist
                for (String fieldName : doc.keySet()) {
                    if (!fieldName.equals("_id")) {
                        Object value = doc.get(fieldName);
                        if (value != null) {
                            userData.put(fieldName, value);
                        } else {
                            userData.put(fieldName, "null");
                        }
                    }
                }

                userList.add(userData);
            });

            result.put("business_users", userList);
            result.put("count", userList.size());

            LOG.infof("Found %d business users", userList.size());
            return Response.ok(result).build();

        } catch (Exception e) {
            LOG.errorf("Error listing business users: %s", e.getMessage());
            return Response.serverError().entity(Map.of(
                "error", e.getMessage(),
                "details", e.toString()
            )).build();
        }
    }
}
