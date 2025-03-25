/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.integration;

import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import pro.olli.models.SyncTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST resource for E2E integration tests
 */
@Path("/test-e2e")
@ApplicationScoped
public class E2ETestResource {

    private static final Logger LOG = Logger.getLogger(E2ETestResource.class);
    
    // For integration testing with billing and E2E tests
    private static final String TEST_AUTH_TOKEN = "test-auth-token-12345";
    private static final String TEST_BUSINESS_ID = "test-business-12345";

    @Inject
    Gson gson;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli_test")
    String databaseName;

    @GET
    @Path("/get-latest-verification")
    @Produces(MediaType.TEXT_PLAIN)
    public String getLatestVerification(@QueryParam("email") String email) {
        // Create a mock verification if it doesn't exist
        String token = "verification_" + System.currentTimeMillis();
        
        // Store it in MongoDB
        Document verification = new Document()
            .append("token", token)
            .append("email", email)
            .append("businessId", TEST_BUSINESS_ID)
            .append("verified", false)
            .append("role", "admin")
            .append("createdAt", System.currentTimeMillis())
            .append("expiresAt", System.currentTimeMillis() + (24 * 60 * 60 * 1000));
            
        mongoClient.getDatabase(databaseName)
            .getCollection("verifications")
            .insertOne(verification);
            
        return token;
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
            .header("Authorization", "Bearer " + TEST_AUTH_TOKEN)
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
        
        // Store in MongoDB as a backup
        Document taskDoc = new Document()
            .append("taskId", taskId)
            .append("businessId", effectiveBusinessId)
            .append("userId", userId)
            .append("source", source)
            .append("folderId", folderId)
            .append("createdAt", System.currentTimeMillis())
            .append("jsonPayload", taskJson);
            
        mongoClient.getDatabase(databaseName)
            .getCollection("sync_tasks")
            .insertOne(taskDoc);
        
        return Response.ok(Map.of("message", "Sync queued", "taskId", taskId)).build();
    }
    
    /**
     * Mock endpoint for checking queue task
     */
    @GET
    @Path("/check-queue-task")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkQueueTask(@QueryParam("taskId") String taskId) {
        LOG.infof("Checking queue task: %s", taskId);
        
        Document taskDoc = mongoClient.getDatabase(databaseName)
            .getCollection("sync_tasks")
            .find(new Document("taskId", taskId))
            .first();
            
        if (taskDoc == null) {
            return Response.ok(Map.of("exists", false)).build();
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("exists", true);
        result.put("businessId", taskDoc.getString("businessId"));
        result.put("userId", taskDoc.getString("userId"));
        result.put("source", taskDoc.getString("source"));
        
        return Response.ok(result).build();
    }
    
    /**
     * Mock endpoint for processing sync task
     */
    @POST
    @Path("/process-sync-task")
    @Produces(MediaType.APPLICATION_JSON)
    public Response processSyncTask(@QueryParam("taskId") String taskId) {
        LOG.infof("Processing sync task: %s", taskId);
        
        Document taskDoc = mongoClient.getDatabase(databaseName)
            .getCollection("sync_tasks")
            .find(new Document("taskId", taskId))
            .first();
            
        if (taskDoc == null) {
            return Response.ok(Map.of("processed", false, "message", "Task not found")).build();
        }
        
        // Mark the task as processed
        mongoClient.getDatabase(databaseName)
            .getCollection("sync_tasks")
            .updateOne(
                new Document("taskId", taskId),
                new Document("$set", new Document()
                    .append("status", "completed")
                    .append("completedAt", System.currentTimeMillis())
                )
            );
        
        return Response.ok(Map.of("processed", true)).build();
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
     * Mock endpoint for sending invitations/verifications
     * This mirrors the production endpoint but is isolated for testing
     */
    @POST
    @Path("/auth/invite")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendInvite(Map<String, String> inviteRequest) {
        LOG.infof("Sending invite to email: %s for business: %s", 
                 inviteRequest.get("email"), inviteRequest.get("businessId"));
        
        String email = inviteRequest.get("email");
        String businessId = inviteRequest.get("businessId");
        
        // Create a verification token
        String token = getLatestVerification(email);
        
        return Response.ok(Map.of("message", "Verification email sent")).build();
    }
}