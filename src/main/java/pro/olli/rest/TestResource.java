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
import jakarta.inject.Named;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import pro.olli.ai.AiConfiguredService;
import pro.olli.ai.AiImplementationType;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.EmbeddingService;
import pro.olli.ai.QueryService;
import pro.olli.models.BusinessUser;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.SyncTask;
import pro.olli.models.User;
import pro.olli.models.Verification;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.Factory;
import pro.olli.repository.SyncRepository;
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
    @Named("mongodb-sync") 
    SyncRepository syncRepository;

    @Inject
    TaskQueueService taskQueueService;

    @Inject
    SyncWorker syncWorker;

    @Inject
    Gson gson;

    @Inject
    MongoClient mongoClient;

    @Inject
    @AiConfiguredService
    EmbeddingService embeddingService;

    @Inject
    @AiConfiguredService
    QueryService queryService;

    @Inject
    io.vertx.mutiny.core.Vertx vertx;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli")
    String databaseName;

    @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434")
    String ollamaEndpoint;

    @ConfigProperty(name = "ollama.embedding.model", defaultValue = "tinyllama")
    String ollamaModel;

    @ConfigProperty(name = "ollama.embedding.dimension", defaultValue = "768")
    int embeddingDimension;

    // For integration testing with billing and E2E tests
    private String testAuthToken = "test-auth-token-12345";
    private boolean authDisabled = true; // Set to true to disable auth for tests


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
        LOG.info("Simple health check called - TestResource is active");
        return "OK - TestResource is active";
    }

    @GET
    @Path("/check-ai-config")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> checkAiConfig() {
        LOG.info("Checking AI service configuration");

        // Get class names of the injected services
        String embeddingServiceClass = embeddingService.getClass().getName();
        String queryServiceClass = queryService.getClass().getName();

        Map<String, Object> config = new HashMap<>();
        config.put("embeddingServiceClass", embeddingServiceClass);
        config.put("queryServiceClass", queryServiceClass);
        config.put("ollamaEndpoint", ollamaEndpoint);
        config.put("ollamaModel", ollamaModel);
        config.put("embeddingDimension", embeddingDimension);

        // We don't have direct access to the health check method through the interface
        // So just return the config info
        return Uni.createFrom().item(Response.ok(config).build());
    }

    @GET
    @Path("/check-ollama4j")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> checkOllama4j() {
        LOG.info("Checking AI services configuration (extended info)");

        Map<String, Object> config = new HashMap<>();
        config.put("embeddingServiceType", embeddingService.getClass().getName());
        config.put("queryServiceType", queryService.getClass().getName());
        config.put("ollamaEndpoint", ollamaEndpoint);
        config.put("ollamaModel", ollamaModel);
        config.put("embeddingDimension", embeddingDimension);

        // Check if Ollama is running first
        try {
            URL url = new URL(ollamaEndpoint + "/api/tags");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);

            try {
                int responseCode = connection.getResponseCode();
                config.put("ollamaAvailable", responseCode == 200);
                config.put("ollamaResponseCode", responseCode);

                if (responseCode != 200) {
                    config.put("serverStatus", "Ollama server returned unexpected status code: " + responseCode);
                    config.put("troubleshooting", "Run ./test/config/start-ollama.sh to start Ollama");
                    return Uni.createFrom().item(Response.ok(config).build());
                }
            } catch (Exception e) {
                config.put("ollamaAvailable", false);
                config.put("ollamaError", e.getMessage());
                config.put("serverStatus", "Failed to connect to Ollama server");
                config.put("troubleshooting", "Run ./test/config/start-ollama.sh to start Ollama");
                return Uni.createFrom().item(Response.ok(config).build());
            }

            // Try a direct API call
            URL testUrl = new URL(ollamaEndpoint + "/api/generate");
            HttpURLConnection testConn = (HttpURLConnection) testUrl.openConnection();
            testConn.setRequestMethod("POST");
            testConn.setRequestProperty("Content-Type", "application/json");
            testConn.setDoOutput(true);

            String testJson = String.format("{\"model\":\"%s\",\"prompt\":\"test\",\"stream\":false}", ollamaModel);
            try (java.io.OutputStream os = testConn.getOutputStream()) {
                byte[] input = testJson.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            try {
                int testResponseCode = testConn.getResponseCode();
                config.put("directApiTest", testResponseCode == 200);
                config.put("directApiResponseCode", testResponseCode);

                if (testResponseCode != 200) {
                    config.put("modelStatus", "Model may not be available. Response code: " + testResponseCode);
                    config.put("troubleshooting", "Run: ollama pull " + ollamaModel);
                    return Uni.createFrom().item(Response.ok(config).build());
                }
            } catch (Exception e) {
                config.put("directApiTest", false);
                config.put("directApiError", e.getMessage());
                return Uni.createFrom().item(Response.ok(config).build());
            }

            config.put("serverStatus", "Running");
            config.put("modelStatus", "Available");

        } catch (Exception e) {
            config.put("checkError", e.getMessage());
        }

        // Test the embedding service
        return embeddingService.generateEmbedding("This is a test embedding")
            .map(embedding -> {
                config.put("embeddingGenerated", true);
                config.put("embeddingLength", embedding.size());
                config.put("embedding", embedding.subList(0, Math.min(5, embedding.size())));
                config.put("status", "OK");
                return Response.ok(config).build();
            })
            .onFailure().recoverWithItem(e -> {
                config.put("embeddingGenerated", false);
                config.put("embeddingError", e.getMessage());

                if (e.getMessage() != null &&
                    e.getMessage().contains("okhttp3.ResponseBody.byteStream()")) {
                    config.put("rootCause", "Null Response Body");
                    config.put("diagnosis", "This typically happens when Ollama is not running");
                    config.put("solution", "Start Ollama with ./test/config/start-ollama.sh");
                } else {
                    config.put("rootCause", "Unknown Error");
                    config.put("exceptionType", e.getClass().getName());
                }

                return Response.ok(config).build();
            });
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

    // --- Endpoints for Billing Integration Testing ---

    /**
     * Creates a test business for integration tests
     */
    @POST
    @Path("/test/create-business")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createTestBusiness(Map<String, Object> business) {
        LOG.infof("Creating test business for integration testing: %s", business);

        try {
            String businessId = (String) business.get("id");
            String name = (String) business.get("name");
            String userId = (String) business.get("userId");

            if (businessId == null || name == null) {
                return Response.status(400).entity(Map.of("error", "Business ID and name are required")).build();
            }

            // Store this in MongoDB for testing
            Document businessDoc = new Document()
                .append("id", businessId)
                .append("name", name)
                .append("userId", userId)
                .append("createdAt", System.currentTimeMillis())
                .append("updatedAt", System.currentTimeMillis())
                .append("subscriptionPlan", "free")
                .append("subscriptionStatus", "trial");

            mongoClient.getDatabase(databaseName)
                .getCollection("businesses")
                .insertOne(businessDoc);

            return Response.ok(business).build();
        } catch (Exception e) {
            LOG.errorf("Error creating test business: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Deletes a test business after integration tests
     */
    @DELETE
    @Path("/test/cleanup-business")
    public Response cleanupTestBusiness(@HeaderParam("X-User-Id") String userId,
                                       @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Cleaning up test business: %s for user: %s", businessId, userId);

        try {
            // Delete business from MongoDB
            if (businessId != null) {
                mongoClient.getDatabase(databaseName)
                    .getCollection("businesses")
                    .deleteMany(new Document("id", businessId));

                // Also delete related data
                mongoClient.getDatabase(databaseName)
                    .getCollection("transactions")
                    .deleteMany(new Document("businessId", businessId));
            }

            // Delete user if specified
            if (userId != null) {
                mongoClient.getDatabase(databaseName)
                    .getCollection("users")
                    .deleteMany(new Document("id", userId));

                // Delete business_user relationship
                mongoClient.getDatabase(databaseName)
                    .getCollection("business_users")
                    .deleteMany(new Document("userId", userId));
            }

            return Response.noContent().build();
        } catch (Exception e) {
            LOG.errorf("Error cleaning up test business: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Mock endpoint for billing integration tests to create a payment method
     */
    @POST
    @Path("/test-integration/billing/payment-methods")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPaymentMethod(Map<String, Object> paymentMethod,
                                      @HeaderParam("X-User-Id") String userId,
                                      @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Creating test payment method for business: %s", businessId);

        // Generate a test payment method ID
        String paymentMethodId = "pm_test_" + System.currentTimeMillis();

        Map<String, Object> response = new HashMap<>();
        response.put("id", paymentMethodId);
        response.put("type", "card");
        response.put("card", Map.of(
            "brand", "visa",
            "last4", "4242",
            "exp_month", paymentMethod.get("expiryMonth"),
            "exp_year", paymentMethod.get("expiryYear")
        ));

        return Response.ok(response).build();
    }

    /**
     * Mock endpoint for billing integration tests to create a payment
     */
    @POST
    @Path("/test-integration/billing/payment")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPayment(Map<String, Object> payment,
                               @HeaderParam("X-User-Id") String userId,
                               @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Creating test payment for business: %s", businessId);

        // Generate a test payment intent ID
        String paymentIntentId = "pi_test_" + System.currentTimeMillis();
        String clientSecret = "pi_test_secret_" + System.currentTimeMillis();

        // Store a transaction record
        Document transaction = new Document()
            .append("id", "txn_" + System.currentTimeMillis())
            .append("businessId", businessId)
            .append("userId", userId)
            .append("externalId", paymentIntentId)
            .append("amount", payment.get("amount"))
            .append("currency", payment.get("currency"))
            .append("description", payment.get("description"))
            .append("paymentMethodId", payment.get("paymentMethodId"))
            .append("status", "PENDING")
            .append("type", "PAYMENT")
            .append("createdAt", System.currentTimeMillis());

        mongoClient.getDatabase(databaseName)
            .getCollection("transactions")
            .insertOne(transaction);

        Map<String, Object> response = new HashMap<>();
        response.put("id", paymentIntentId);
        response.put("clientSecret", clientSecret);
        response.put("status", "requires_confirmation");
        response.put("amount", payment.get("amount"));
        response.put("currency", payment.get("currency"));

        return Response.ok(response).build();
    }

    /**
     * Mock endpoint for billing integration tests to get transactions
     */
    @GET
    @Path("/test-integration/billing/transactions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTransactions(@HeaderParam("X-User-Id") String userId,
                                  @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Getting transactions for business: %s", businessId);

        List<Document> transactions = new ArrayList<>();
        mongoClient.getDatabase(databaseName)
            .getCollection("transactions")
            .find(new Document("businessId", businessId))
            .forEach(doc -> transactions.add(doc));

        List<Map<String, Object>> result = new ArrayList<>();

        for (Document doc : transactions) {
            Map<String, Object> transaction = new HashMap<>();
            for (String key : doc.keySet()) {
                if (key.equals("_id")) continue;
                transaction.put(key, doc.get(key));
            }
            result.add(transaction);
        }

        return Response.ok(result).build();
    }

    /**
     * Mock endpoint for billing integration tests to create a subscription
     */
    @POST
    @Path("/test-integration/billing/subscribe")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSubscription(Map<String, Object> subscription,
                                    @HeaderParam("X-User-Id") String userId,
                                    @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Creating test subscription for business: %s with plan: %s",
                 businessId, subscription.get("plan"));

        // Generate a test subscription ID
        String subscriptionId = "sub_test_" + System.currentTimeMillis();

        // Update the business subscription plan
        mongoClient.getDatabase(databaseName)
            .getCollection("businesses")
            .updateOne(
                new Document("id", businessId),
                new Document("$set", new Document()
                    .append("subscriptionPlan", subscription.get("plan"))
                    .append("subscriptionStatus", "active")
                    .append("paymentMethodId", subscription.get("paymentMethodId"))
                    .append("subscriptionId", subscriptionId)
                    .append("subscriptionStartDate", System.currentTimeMillis())
                    .append("nextBillingDate", System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000)) // 30 days
                )
            );

        Map<String, Object> response = new HashMap<>();
        response.put("id", subscriptionId);
        response.put("status", "active");
        response.put("plan", subscription.get("plan"));
        response.put("currentPeriodEnd", System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000));

        return Response.ok(response).build();
    }

    /**
     * Mock endpoint for billing integration tests to get subscription
     */
    @GET
    @Path("/test-integration/billing/subscription")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSubscription(@HeaderParam("X-User-Id") String userId,
                                  @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Getting subscription for business: %s", businessId);

        Document business = mongoClient.getDatabase(databaseName)
            .getCollection("businesses")
            .find(new Document("id", businessId))
            .first();

        if (business == null) {
            return Response.status(404).entity(Map.of("error", "Business not found")).build();
        }

        Map<String, Object> subscription = new HashMap<>();
        subscription.put("plan", business.getString("subscriptionPlan"));
        subscription.put("status", business.getString("subscriptionStatus"));
        subscription.put("subscriptionId", business.getString("subscriptionId"));

        Long startDate = business.getLong("subscriptionStartDate");
        if (startDate != null) {
            subscription.put("startDate", startDate);
        }

        Long nextBillingDate = business.getLong("nextBillingDate");
        if (nextBillingDate != null) {
            subscription.put("nextBillingDate", nextBillingDate);
        }

        return Response.ok(subscription).build();
    }

    /**
     * Mock endpoint for billing integration tests to update subscription
     */
    @PUT
    @Path("/test-integration/billing/subscription")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateSubscription(Map<String, Object> update,
                                     @HeaderParam("X-User-Id") String userId,
                                     @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Updating subscription for business: %s to plan: %s",
                 businessId, update.get("plan"));

        // Update the business subscription plan
        mongoClient.getDatabase(databaseName)
            .getCollection("businesses")
            .updateOne(
                new Document("id", businessId),
                new Document("$set", new Document()
                    .append("subscriptionPlan", update.get("plan"))
                    .append("updatedAt", System.currentTimeMillis())
                )
            );

        // Get the updated business
        Document business = mongoClient.getDatabase(databaseName)
            .getCollection("businesses")
            .find(new Document("id", businessId))
            .first();

        if (business == null) {
            return Response.status(404).entity(Map.of("error", "Business not found")).build();
        }

        Map<String, Object> subscription = new HashMap<>();
        subscription.put("plan", business.getString("subscriptionPlan"));
        subscription.put("status", business.getString("subscriptionStatus"));
        subscription.put("subscriptionId", business.getString("subscriptionId"));

        return Response.ok(subscription).build();
    }

    /**
     * Mock endpoint for billing integration tests to cancel subscription
     */
    @DELETE
    @Path("/test-integration/billing/subscription")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelSubscription(@HeaderParam("X-User-Id") String userId,
                                     @HeaderParam("X-Business-Id") String businessId) {
        LOG.infof("Canceling subscription for business: %s", businessId);

        // Update the business subscription status
        mongoClient.getDatabase(databaseName)
            .getCollection("businesses")
            .updateOne(
                new Document("id", businessId),
                new Document("$set", new Document()
                    .append("subscriptionStatus", "canceling")
                    .append("updatedAt", System.currentTimeMillis())
                )
            );

        Map<String, Object> response = new HashMap<>();
        response.put("status", "canceling");

        return Response.ok(response).build();
    }

    /**
     * Mock endpoint for billing integration tests to handle webhook
     */
    @POST
    @Path("/test-integration/billing/webhook")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response handleWebhook(String payload,
                                @HeaderParam("Stripe-Signature") String signature) {
        LOG.infof("Handling webhook with signature: %s", signature);

        try {
            // Parse the webhook payload as JSON
            Map<String, Object> event = gson.fromJson(payload, Map.class);

            // Get the event type
            String eventType = (String) event.get("type");
            LOG.infof("Webhook event type: %s", eventType);

            if ("payment_intent.succeeded".equals(eventType)) {
                Map<String, Object> data = (Map<String, Object>) event.get("data");
                Map<String, Object> object = (Map<String, Object>) data.get("object");
                String paymentIntentId = (String) object.get("id");

                // Update the transaction status to COMPLETED
                mongoClient.getDatabase(databaseName)
                    .getCollection("transactions")
                    .updateMany(
                        new Document("externalId", paymentIntentId),
                        new Document("$set", new Document()
                            .append("status", "COMPLETED")
                            .append("updatedAt", System.currentTimeMillis())
                        )
                    );
            }

            return Response.ok().build();
        } catch (Exception e) {
            LOG.errorf("Error handling webhook: %s", e.getMessage());
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Simple POJO to hold Ollama request data
     */
    public static class OllamaRequest {
        private String model;
        private String prompt;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
    }

    // NOTE: The direct-ollama and simple-ollama implementations below are for testing and debugging
    // For production code, use the DirectOllamaQueryService and DirectOllamaEmbeddingService classes
    // You can set the ollama.implementation=direct property to enable the direct implementation

    /**
     * Model for embedding generation request
     */
    public static class EmbeddingRequest {
        private String text;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    /**
     * Test endpoint for generating embeddings
     */
    @POST
    @Path("/generate-embedding")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> generateEmbedding(EmbeddingRequest request) {
        LOG.infof("Generating embedding for text: %s",
            request.getText() != null ? request.getText().substring(0, Math.min(50, request.getText().length())) + "..." : "null");

        if (request.getText() == null || request.getText().isEmpty()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "Text is required for embedding generation"))
                .build());
        }

        return embeddingService.generateEmbedding(request.getText())
            .map(embedding -> {
                Map<String, Object> response = new HashMap<>();
                response.put("embedding_size", embedding.size());
                response.put("embedding_preview", embedding.subList(0, Math.min(5, embedding.size())));
                response.put("type", embeddingService.getClass().getName());
                response.put("timestamp", System.currentTimeMillis());

                return Response.ok(response).build();
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Error generating embedding: %s", e.getMessage());
                return Response.serverError()
                    .entity(Map.of(
                        "error", "Failed to generate embedding",
                        "message", e.getMessage(),
                        "type", e.getClass().getName()
                    ))
                    .build();
            });
    }

    /**
     * Simple JSON test endpoint that returns a hardcoded response
     * Used to debug JSON formatting issues
     */
    @GET
    @Path("/test-json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response testJson() {
        LOG.info("Testing JSON response");

        Map<String, Object> response = new HashMap<>();
        response.put("model", "test-model");
        response.put("response", "This is a test response");
        response.put("created_at", System.currentTimeMillis());
        response.put("done", true);
        response.put("total_duration", 1234);

        return Response.ok(response).build();
    }

    /**
     * Simple string test endpoint that returns a hardcoded JSON string
     * Used to debug JSON formatting issues
     */
    @GET
    @Path("/test-string")
    @Produces(MediaType.APPLICATION_JSON)
    public Response testJsonString() {
        LOG.info("Testing JSON string response");

        String jsonString = "{\"model\":\"test-model\",\"response\":\"This is a test response\",\"createdAt\":"
            + System.currentTimeMillis() + ",\"done\":true,\"totalDuration\":1234}";

        return Response.ok(jsonString)
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    /**
     * Simplified direct Ollama API endpoint using WebClient
     * This implementation is non-blocking and should not cause blocked thread warnings
     */
    @POST
    @Path("/simple-ollama")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> simpleOllamaQuery(OllamaRequest request) {
        LOG.infof("Processing simple Ollama query with model %s", request.getModel());

        String prompt = request.getPrompt();
        String model = request.getModel();

        if (prompt == null || prompt.isEmpty()) {
            return Uni.createFrom().item(
                Response.status(400)
                    .entity("{\"error\":\"Prompt is required\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
        }

        if (model == null || model.isEmpty()) {
            model = ollamaModel; // Use default model if not specified
        }

        // Use WebClient to make non-blocking request
        io.vertx.mutiny.ext.web.client.WebClient webClient = io.vertx.mutiny.ext.web.client.WebClient.create(vertx);

        // Parse URL for host, port, path
        String urlString = ollamaEndpoint + "/api/generate";
        java.net.URI uri;
        try {
            uri = new java.net.URI(urlString);
        } catch (Exception e) {
            LOG.errorf("Invalid URL: %s", urlString, e);
            return Uni.createFrom().item(
                Response.status(500)
                    .entity("{\"error\":\"Invalid Ollama endpoint URL\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(uri.getScheme()) ? 443 : 80;
        }
        String path = uri.getPath();

        // Build request body
        io.vertx.core.json.JsonObject requestBody = new io.vertx.core.json.JsonObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("stream", false);

        final String finalModel = model;

        return webClient.post(port, host, path)
            .timeout(30000) // 30 second timeout
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(requestBody)
            .map(response -> {
                LOG.infof("Received response from Ollama with status %d", response.statusCode());

                if (response.statusCode() == 200) {
                    // Parse successful response
                    io.vertx.core.json.JsonObject jsonResponse = response.bodyAsJsonObject();
                    String ollamaResponse = jsonResponse.getString("response");

                    // Create response object
                    io.vertx.core.json.JsonObject responseJson = new io.vertx.core.json.JsonObject()
                        .put("model", finalModel)
                        .put("response", ollamaResponse);

                    return Response.ok(responseJson.encode())
                        .type(MediaType.APPLICATION_JSON)
                        .build();
                } else {
                    // Handle error
                    LOG.errorf("Ollama error response: %s", response.bodyAsString());

                    io.vertx.core.json.JsonObject errorJson = new io.vertx.core.json.JsonObject()
                        .put("error", "Ollama API error")
                        .put("status", response.statusCode())
                        .put("message", response.bodyAsString());

                    return Response.status(response.statusCode())
                        .entity(errorJson.encode())
                        .type(MediaType.APPLICATION_JSON)
                        .build();
                }
            })
            .onFailure().recoverWithItem(error -> {
                LOG.errorf("Error calling Ollama API: %s", error.getMessage(), error);

                io.vertx.core.json.JsonObject errorJson = new io.vertx.core.json.JsonObject()
                    .put("error", "Failed to communicate with Ollama")
                    .put("message", error.getMessage())
                    .put("type", error.getClass().getName());

                return Response.status(500)
                    .entity(errorJson.encode())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
            });
    }

    /**
     * Direct Ollama API endpoint using WebClient
     * Almost the same as simple-ollama but preserves more of the Ollama response
     */
    @POST
    @Path("/direct-ollama")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> directOllamaQuery(OllamaRequest request) {
        LOG.infof("Processing direct Ollama query with model %s", request.getModel());

        String prompt = request.getPrompt();
        String model = request.getModel();

        if (prompt == null || prompt.isEmpty()) {
            return Uni.createFrom().item(
                Response.status(400)
                    .entity("{\"error\":\"Prompt is required\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
        }

        if (model == null || model.isEmpty()) {
            model = ollamaModel; // Use default model if not specified
        }

        // Use WebClient to make non-blocking request
        io.vertx.mutiny.ext.web.client.WebClient webClient = io.vertx.mutiny.ext.web.client.WebClient.create(vertx);

        // Parse URL for host, port, path
        String urlString = ollamaEndpoint + "/api/generate";
        java.net.URI uri;
        try {
            uri = new java.net.URI(urlString);
        } catch (Exception e) {
            LOG.errorf("Invalid URL: %s", urlString, e);
            return Uni.createFrom().item(
                Response.status(500)
                    .entity("{\"error\":\"Invalid Ollama endpoint URL\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(uri.getScheme()) ? 443 : 80;
        }
        String path = uri.getPath();

        // Build request body
        io.vertx.core.json.JsonObject requestBody = new io.vertx.core.json.JsonObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("stream", false);

        final String finalModel = model;

        return webClient.post(port, host, path)
            .timeout(60000) // 60 second timeout
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(requestBody)
            .map(response -> {
                LOG.infof("Received response from Ollama with status %d", response.statusCode());

                if (response.statusCode() == 200) {
                    // For the direct API, just pass through the response with minimal changes
                    io.vertx.core.json.JsonObject jsonResponse = response.bodyAsJsonObject();

                    // Add a couple extra fields but otherwise keep original response
                    jsonResponse.put("model", finalModel);
                    jsonResponse.put("query_time", System.currentTimeMillis());

                    return Response.ok(jsonResponse.encode())
                        .type(MediaType.APPLICATION_JSON)
                        .build();
                } else {
                    // Handle error
                    LOG.errorf("Ollama error response: %s", response.bodyAsString());

                    io.vertx.core.json.JsonObject errorJson = new io.vertx.core.json.JsonObject()
                        .put("error", "Ollama API error")
                        .put("status", response.statusCode())
                        .put("message", response.bodyAsString());

                    return Response.status(response.statusCode())
                        .entity(errorJson.encode())
                        .type(MediaType.APPLICATION_JSON)
                        .build();
                }
            })
            .onFailure().recoverWithItem(error -> {
                LOG.errorf("Error calling Ollama API: %s", error.getMessage(), error);

                io.vertx.core.json.JsonObject errorJson = new io.vertx.core.json.JsonObject()
                    .put("error", "Failed to communicate with Ollama")
                    .put("message", error.getMessage())
                    .put("type", error.getClass().getName());

                return Response.status(500)
                    .entity(errorJson.encode())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
            });
    }

    // --- Endpoints for E2E Integration Testing ---

    /**
     * Integration endpoint for document storage
     */
    @POST
    @Path("/integration-store")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> storeDocumentIntegration(DocumentRequest request) {
        return storeDocument(request);
    }
    
    /**
     * Integration endpoint for RAG query
     */
    @POST
    @Path("/integration-query")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> queryIntegration(QueryRequest request) {
        return query(request);
    }
    
    /**
     * Provides an auth token for E2E tests
     */
    @POST
    @Path("/auth/register")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(Map<String, String> registerRequest) {
        LOG.infof("Registering user with email: %s and token: %s",
                 registerRequest.get("email"), registerRequest.get("token"));

        String email = registerRequest.get("email");
        String token = registerRequest.get("token");

        // Store the user in MongoDB for testing
        String userId = "user-" + System.currentTimeMillis();

        Document userDoc = new Document()
            .append("id", userId)
            .append("email", email)
            .append("createdAt", System.currentTimeMillis())
            .append("updatedAt", System.currentTimeMillis());

        mongoClient.getDatabase(databaseName)
            .getCollection("users")
            .insertOne(userDoc);

        // Store the business user association
        // Extract business ID from verification token - in real code this would be looked up
        String businessId = TEST_BUSINESS_ID;
        if (token != null && !token.isEmpty()) {
            Document verification = mongoClient.getDatabase(databaseName)
                .getCollection("verifications")
                .find(new Document("token", token))
                .first();

            if (verification != null) {
                businessId = verification.getString("businessId");

                // Mark verification as verified
                mongoClient.getDatabase(databaseName)
                    .getCollection("verifications")
                    .updateOne(
                        new Document("token", token),
                        new Document("$set", new Document()
                            .append("verified", true)
                            .append("updatedAt", System.currentTimeMillis())
                        )
                    );
            }
        }

        Document businessUserDoc = new Document()
            .append("userId", userId)
            .append("businessId", businessId)
            .append("email", email)
            .append("role", "admin")
            .append("createdAt", System.currentTimeMillis());

        mongoClient.getDatabase(databaseName)
            .getCollection("business_users")
            .insertOne(businessUserDoc);

        // Return auth token for testing
        return Response.ok()
            .header("Authorization", "Bearer " + testAuthToken)
            .entity(Map.of("userId", userId, "businessId", businessId))
            .build();
    }

    /**
     * Mock endpoint for E2E testing for user profile
     */
    @GET
    @Path("/auth/profile")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUserProfile(@HeaderParam("Authorization") String authorization) {
        LOG.infof("Getting user profile with authorization: %s", authorization);

        // In real code, we'd extract the user from the token
        // Here we'll just return a hard-coded response for testing
        Map<String, Object> profile = new HashMap<>();
        profile.put("email", "test@example.com");
        profile.put("businessId", TEST_BUSINESS_ID);
        profile.put("role", "admin");

        return Response.ok(profile).build();
    }

    // Test constant for E2E tests
    private static final String TEST_BUSINESS_ID = "test-business-12345";

    /**
     * Mock endpoint for syncing local directory
     */
    @GET
    @Path("/auth/sync/local")
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncLocal(@HeaderParam("Authorization") String authorization,
                           @QueryParam("folderId") String folderId,
                           @QueryParam("businessId") String businessId) {
        LOG.infof("Syncing local directory for business: %s, folder: %s", businessId, folderId);

        // Generate a task ID
        String taskId = "task-" + System.currentTimeMillis();

        // Create a sync task
        String effectiveBusinessId = businessId != null ? businessId : TEST_BUSINESS_ID;
        String userId = "user-12345";
        String source = "local";
        SyncTask task = new SyncTask(effectiveBusinessId, userId, source, folderId);

        // Convert to JSON and store in queue
        String taskJson = gson.toJson(task);
        LOG.infof("Created sync task: %s", taskJson);

        // Add to queue if there's a queue service available
        try {
            taskQueueService.enqueueTask(taskId, taskJson);
        } catch (Exception e) {
            LOG.warnf("Could not enqueue task, likely no queue available: %s", e.getMessage());
            // Store in MongoDB as a backup
            Document taskDoc = new Document()
                .append("taskId", taskId)
                .append("businessId", task.getBusinessId())
                .append("userId", task.getUserId())
                .append("source", task.getSource())
                .append("folderId", task.getFolderId())
                .append("createdAt", System.currentTimeMillis())
                .append("jsonPayload", taskJson);

            mongoClient.getDatabase(databaseName)
                .getCollection("sync_tasks")
                .insertOne(taskDoc);
        }

        return Response.ok(Map.of("message", "Sync queued", "taskId", taskId)).build();
    }

    /**
     * Mock endpoint for checking sync status
     */
    @GET
    @Path("/auth/sync/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSyncStatus(@HeaderParam("Authorization") String authorization,
                               @QueryParam("taskId") String taskId) {
        LOG.infof("Checking sync status for task: %s", taskId);

        // Create a mock completed status
        Map<String, Object> status = new HashMap<>();
        status.put("taskId", taskId);
        status.put("status", "completed");
        status.put("fileCount", 5);
        status.put("totalBytes", 1024 * 1024);
        status.put("startTime", System.currentTimeMillis() - 5000);
        status.put("endTime", System.currentTimeMillis());

        return Response.ok(status).build();
    }

    /**
     * Mock endpoint for chat API
     */
    @POST
    @Path("/chat/query")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response chatQuery(@HeaderParam("Authorization") String authorization,
                           pro.olli.models.ChatRequest request) {
        LOG.infof("Processing chat query: %s", request.prompt);

        // Create a mock response
        Map<String, Object> response = new HashMap<>();

        if (request.prompt.contains("ACME") || request.prompt.contains("contract")) {
            response.put("response", "The ACME contract specifies a 12-month service agreement with " +
                "quarterly payments of $5,000. The contract includes clauses for service level " +
                "agreements (SLAs) and terms for renewal and termination.");
        } else {
            response.put("response", "I'm sorry, I don't have specific information about that topic. " +
                "Could you try asking another question about documents you've synced?");
        }

        response.put("sources", List.of("contract_acme.pdf"));

        return Response.ok(response).build();
    }

    /**
     * Model for storing document for RAG testing
     */
    public static class DocumentRequest {
        private String businessId;
        private String userId;
        private String filename;
        private String content;

        public String getBusinessId() { return businessId; }
        public void setBusinessId(String businessId) { this.businessId = businessId; }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    /**
     * Store document for RAG testing
     */
    @POST
    @Path("/store-document")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> storeDocument(DocumentRequest request) {
        LOG.infof("Storing document for RAG: %s", request.getFilename());

        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "Document content is required"))
                .build());
        }

        if (request.getFilename() == null || request.getFilename().trim().isEmpty()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "Filename is required"))
                .build());
        }

        String businessId = request.getBusinessId() != null ?
            request.getBusinessId() : "test-business";
        String userId = request.getUserId() != null ?
            request.getUserId() : "test-user";

        // Check if we have direct access to an enhanced RAG service that can handle chunking
        if (queryService instanceof pro.olli.ai.ollama.EnhancedRAGOllamaQueryService) {
            LOG.info("Using EnhancedRAGOllamaQueryService for document storage");

            pro.olli.ai.ollama.EnhancedRAGOllamaQueryService ragService =
                (pro.olli.ai.ollama.EnhancedRAGOllamaQueryService) queryService;

            return ragService.storeDocumentChunks(businessId, userId, request.getFilename(), request.getContent())
                .map(v -> Response.ok(Map.of(
                    "success", true,
                    "message", "Document stored with enhanced RAG service",
                    "businessId", businessId,
                    "userId", userId,
                    "filename", request.getFilename()
                )).build())
                .onFailure().recoverWithItem(e -> {
                    LOG.errorf("Error storing document: %s", e.getMessage());
                    return Response.serverError()
                        .entity(Map.of("error", e.getMessage()))
                        .build();
                });
        } else {
            // Fallback to simple approach with no chunking - whole document
            LOG.info("Using simple approach for document storage (no chunking)");

            return embeddingService.generateEmbedding(request.getContent())
                .flatMap(embedding -> {
                    // Create a unique ID for this document
                    String docId = "doc-" + System.currentTimeMillis();

                    // Store the document and embedding in MongoDB
                    return syncRepository.storeEmbedding(docId, businessId, userId,
                            request.getContent(), embedding)
                        .map(v -> Response.ok(Map.of(
                            "success", true,
                            "message", "Document stored with simple approach (whole document)",
                            "businessId", businessId,
                            "userId", userId,
                            "filename", request.getFilename(),
                            "embeddingLength", embedding.size()
                        )).build());
                })
                .onFailure().recoverWithItem(e -> {
                    LOG.errorf("Error storing document: %s", e.getMessage());
                    return Response.serverError()
                        .entity(Map.of("error", e.getMessage()))
                        .build();
                });
        }
    }

    /**
     * Model for query request
     */
    public static class QueryRequest {
        private String prompt;
        private List<ChatMessage> history;

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }

        public List<ChatMessage> getHistory() { return history; }
        public void setHistory(List<ChatMessage> history) { this.history = history; }
    }

    /**
     * Test endpoint for RAG query
     */
    @POST
    @Path("/query")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> query(QueryRequest request) {
        LOG.infof("Processing RAG query: %s", request.getPrompt());

        if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "Prompt is required"))
                .build());
        }

        // Set the RAG system properties just in case
        System.setProperty("ollama.rag.user_id", "test-user");
        System.setProperty("ollama.rag.business_id", "test-business");

        // Use the provided query service (whatever implementation is active)
        return queryService.query(request.getPrompt(),
                request.getHistory() != null ? request.getHistory() : List.of())
            .map(response -> {
                String serviceType = queryService.getClass().getSimpleName();

                return Response.ok(Map.of(
                    "response", response,
                    "serviceType", serviceType,
                    "prompt", request.getPrompt()
                )).build();
            })
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Error querying: %s", e.getMessage());
                return Response.serverError()
                    .entity(Map.of("error", e.getMessage()))
                    .build();
            });
    }
}
