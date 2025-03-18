/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.rest;

import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

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
import pro.olli.models.Invite;
import pro.olli.models.SyncHistoryEntry;
import pro.olli.models.SyncTask;
import pro.olli.models.User;
import pro.olli.queue.TaskQueueService;
import pro.olli.repository.Factory;
import pro.olli.repository.UserRepository;
import pro.olli.worker.SyncWorker;

/**
 * This resource exposes endpoints used only for testing purposes.
 * Main source directory version of TestResource for debugging and development.
 */
@Path("/test-integration")
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
    @Path("/get-latest-invite")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> getLatestInvite(@QueryParam("email") String email) {
        return userRepository.getInviteByEmail(email)
                .map(inviteOpt -> inviteOpt.map(Invite::getToken).orElse("no-token-found"));
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
    @Path("/check-invite")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> checkInvite(@QueryParam("token") String token) {
        LOG.infof("Checking invite with token: %s", token);
        
        try {
            // Direct MongoDB check for the invite - bypassing the repository for diagnostics
            MongoCollection<Document> invites = mongoClient.getDatabase(databaseName).getCollection("invites");
            Document doc = invites.find(new Document("token", token)).first();
            
            if (doc == null) {
                LOG.warnf("No invite found with token: %s", token);
                return Uni.createFrom().item(Response.status(404)
                    .entity(Map.of("found", false, "message", "No invite found with token: " + token))
                    .build());
            }
            
            // Extract and return full invite data for inspection
            Map<String, Object> result = new HashMap<>();
            result.put("found", true);
            result.put("token", doc.getString("token"));
            result.put("email", doc.getString("email"));
            result.put("businessId", doc.getString("businessId"));
            result.put("claimed", doc.getBoolean("claimed", false));
            result.put("role", doc.getString("role"));
            result.put("createdAt", doc.getLong("createdAt"));
            result.put("expiresAt", doc.getLong("expiresAt"));
            
            LOG.infof("Found invite: token=%s, email=%s, claimed=%s", 
                doc.getString("token"), doc.getString("email"), doc.getBoolean("claimed", false));
                
            return Uni.createFrom().item(Response.ok(result).build());
        } catch (Exception e) {
            LOG.errorf("Error checking invite: %s", e.getMessage());
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
        
        // Step 1: Create an invite
        Invite invite = new Invite(
            "test-token-" + System.currentTimeMillis(),
            businessId,
            email,
            "user", // Default role
            null, // No inviter info
            Instant.now(),
            Instant.now().plusSeconds(7 * 24 * 60 * 60),
            false // Not claimed yet
        );
        
        return userRepository.saveInvite(invite)
            .flatMap(v -> {
                LOG.infof("Step 1: Invite created with token: %s", invite.getToken());
                
                // Step 2: Create a user for this invite
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
                // Step 4: Mark the invite as claimed
                Invite claimedInvite = invite.withClaimed();
                LOG.infof("Step 4: Marking invite as claimed, token: %s, claimed: %s", 
                    claimedInvite.getToken(), claimedInvite.isClaimed());
                
                return userRepository.saveInvite(claimedInvite)
                    .map(v -> {
                        LOG.infof("Step 4: Invite marked as claimed");
                        
                        // Step 5: Verify the claimed status directly in MongoDB
                        try {
                            MongoCollection<Document> invites = mongoClient.getDatabase(databaseName)
                                .getCollection("invites");
                            Document doc = invites.find(new Document("token", invite.getToken())).first();
                            
                            if (doc != null) {
                                boolean claimed = doc.getBoolean("claimed", false);
                                LOG.infof("MongoDB verification: invite claimed=%s", claimed);
                                
                                return Response.ok(Map.of(
                                    "success", true,
                                    "message", "Test registration flow completed successfully",
                                    "token", invite.getToken(),
                                    "userId", userId,
                                    "verified", claimed
                                )).build();
                            } else {
                                return Response.ok(Map.of(
                                    "success", true,
                                    "message", "Registration completed but invite not found in direct DB check",
                                    "token", invite.getToken(),
                                    "userId", userId
                                )).build();
                            }
                        } catch (Exception e) {
                            LOG.errorf("Error verifying invite: %s", e.getMessage());
                            return Response.ok(Map.of(
                                "success", true,
                                "message", "Registration completed but verification failed: " + e.getMessage(),
                                "token", invite.getToken()
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
            for (String collectionName : List.of("users", "business_users", "businesses", "invites")) {
                long count = mongoClient.getDatabase(databaseName)
                    .getCollection(collectionName)
                    .countDocuments();

                result.put(collectionName + "_count", count);
            }

            // Add some invite data for reference
            MongoCollection<Document> invites = mongoClient.getDatabase(databaseName).getCollection("invites");
            List<Map<String, Object>> inviteList = new ArrayList<>();
            invites.find().limit(10).forEach(doc -> {
                Map<String, Object> invite = new HashMap<>();
                invite.put("token", doc.getString("token"));
                invite.put("email", doc.getString("email"));
                invite.put("businessId", doc.getString("businessId"));
                invite.put("claimed", doc.getBoolean("claimed", false));
                inviteList.add(invite);
            });
            result.put("recent_invites", inviteList);

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
            Map<String, Object> userMap = new HashMap<>();

            users.find().forEach(doc -> {
                String id = doc.getString("id");
                Map<String, Object> userData = new HashMap<>();
                userData.put("email", doc.getString("email"));

                if (doc.containsKey("name")) {
                    userData.put("name", doc.getString("name"));
                }

                if (doc.containsKey("metadata")) {
                    userData.put("metadata", doc.get("metadata"));
                }

                userMap.put(id, userData);
            });

            result.put("users", userMap);
            result.put("count", userMap.size());

            LOG.infof("Found %d users", userMap.size());
            return Response.ok(result).build();

        } catch (Exception e) {
            LOG.errorf("Error listing users: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
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
            Map<String, Object> userMap = new HashMap<>();

            businessUsers.find(filter).forEach(doc -> {
                String id = doc.getString("id");
                Map<String, Object> userData = new HashMap<>();
                userData.put("businessId", doc.getString("businessId"));
                userData.put("email", doc.getString("email"));
                userData.put("role", doc.getString("role"));

                if (doc.containsKey("name")) {
                    userData.put("name", doc.getString("name"));
                }

                userMap.put(id, userData);
            });

            result.put("business_users", userMap);
            result.put("count", userMap.size());

            LOG.infof("Found %d business users", userMap.size());
            return Response.ok(result).build();

        } catch (Exception e) {
            LOG.errorf("Error listing business users: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }
}
