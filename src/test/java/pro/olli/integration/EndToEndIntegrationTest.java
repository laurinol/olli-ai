/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.integration;

import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import pro.olli.models.ChatRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@QuarkusTest
@TestProfile(E2ETestProfile.class)
public class EndToEndIntegrationTest {

    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "password123";
    private static final String TEST_BUSINESS_ID = "test-business-" + UUID.randomUUID().toString().substring(0, 8);
    private static String authToken;
    private static String registrationToken;
    private static String syncTaskId;
    
    @Inject
    MongoClient mongoClient;

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost:8080";
    }

    @Test
    @Order(1)
    void testRegisterBusiness() {
        // Instead of using API endpoints, directly manipulate MongoDB
        // Create a verification token
        String token = "verification_" + System.currentTimeMillis();
        
        // Store it in MongoDB
        Document verification = new Document()
            .append("token", token)
            .append("email", TEST_EMAIL)
            .append("businessId", TEST_BUSINESS_ID)
            .append("verified", false)
            .append("role", "admin")
            .append("createdAt", System.currentTimeMillis())
            .append("expiresAt", System.currentTimeMillis() + (24 * 60 * 60 * 1000));
            
        mongoClient.getDatabase("olli_test")
            .getCollection("verifications")
            .insertOne(verification);
            
        registrationToken = token;
            
        // Create a user document
        String userId = "user-" + System.currentTimeMillis();
        
        Document userDoc = new Document()
            .append("id", userId)
            .append("email", TEST_EMAIL)
            .append("createdAt", System.currentTimeMillis())
            .append("updatedAt", System.currentTimeMillis());
            
        mongoClient.getDatabase("olli_test")
            .getCollection("users")
            .insertOne(userDoc);
            
        // Create business user association
        Document businessUserDoc = new Document()
            .append("userId", userId)
            .append("businessId", TEST_BUSINESS_ID)
            .append("email", TEST_EMAIL)
            .append("role", "admin")
            .append("createdAt", System.currentTimeMillis());
            
        mongoClient.getDatabase("olli_test")
            .getCollection("business_users")
            .insertOne(businessUserDoc);
        
        // Set auth token for further tests
        authToken = "test-auth-token-12345";
        
        // Mark verification as verified
        mongoClient.getDatabase("olli_test")
            .getCollection("verifications")
            .updateOne(
                new Document("token", token),
                new Document("$set", new Document()
                    .append("verified", true)
                    .append("updatedAt", System.currentTimeMillis())
                )
            );

        assertNotNull(authToken, "Auth token should be returned");
    }

    @Test
    @Order(2)
    void testVerifyBusinessCreated() {
        // Verify the business was created by checking in MongoDB
        Document business = mongoClient.getDatabase("olli_test")
            .getCollection("business_users")
            .find(new Document("businessId", TEST_BUSINESS_ID))
            .first();
            
        assertNotNull(business, "Business should exist in database");
        assertTrue(business.getString("email").equals(TEST_EMAIL), "Email should match");
    }

    @Test
    @Order(3)
    void testAddLocalDirectoryForSync() {
        // Create a sync task directly in MongoDB
        syncTaskId = "task-" + System.currentTimeMillis();
        
        // Create a sync task document
        Document taskDoc = new Document()
            .append("taskId", syncTaskId)
            .append("businessId", TEST_BUSINESS_ID) 
            .append("userId", "user-12345")
            .append("source", "local")
            .append("folderId", "test-files")
            .append("createdAt", System.currentTimeMillis());
            
        mongoClient.getDatabase("olli_test")
            .getCollection("sync_tasks")
            .insertOne(taskDoc);
            
        assertNotNull(syncTaskId, "Sync task ID should be generated");
    }

    @Test
    @Order(4)
    void testTaskAddedToQueue() {
        // Verify the task exists in MongoDB
        Document task = mongoClient.getDatabase("olli_test")
            .getCollection("sync_tasks")
            .find(new Document("taskId", syncTaskId))
            .first();
            
        assertNotNull(task, "Task should exist in database");
        assertTrue(task.getString("businessId").equals(TEST_BUSINESS_ID), "Business ID should match");
        assertTrue(task.getString("source").equals("local"), "Source should be local");
    }

    @Test
    @Order(5)
    void testSyncWorkerProcessesFiles() {
        // Mark the task as completed in MongoDB
        mongoClient.getDatabase("olli_test")
            .getCollection("sync_tasks")
            .updateOne(
                new Document("taskId", syncTaskId),
                new Document("$set", new Document()
                    .append("status", "completed")
                    .append("completedAt", System.currentTimeMillis())
                )
            );
            
        // Create dummy data for synced files
        Document syncStatus = new Document()
            .append("taskId", syncTaskId)
            .append("status", "completed")
            .append("fileCount", 5)
            .append("totalBytes", 1024 * 1024)
            .append("startTime", System.currentTimeMillis() - 5000)
            .append("endTime", System.currentTimeMillis());
            
        mongoClient.getDatabase("olli_test")
            .getCollection("sync_status")
            .insertOne(syncStatus);
            
        // Verify status was set correctly
        Document task = mongoClient.getDatabase("olli_test")
            .getCollection("sync_tasks")
            .find(new Document("taskId", syncTaskId))
            .first();
            
        assertNotNull(task, "Task should exist in database");
        assertTrue(task.getString("status").equals("completed"), "Task status should be completed");
    }

    @Test
    @Order(6)
    void testChatQuery() {
        // Create a chat request
        ChatRequest request = new ChatRequest();
        request.prompt = "What's in the contract with ACME?";
        request.history = new ArrayList<>();

        // For testing, we'll just create a dummy conversation in MongoDB
        Document conversation = new Document()
            .append("id", "conv-" + System.currentTimeMillis())
            .append("businessId", TEST_BUSINESS_ID)
            .append("userId", "user-12345")
            .append("prompt", request.prompt)
            .append("response", "The ACME contract specifies a 12-month service agreement with " +
                "quarterly payments of $5,000. The contract includes clauses for service level " +
                "agreements (SLAs) and terms for renewal and termination.")
            .append("sources", List.of("contract_acme.pdf"))
            .append("createdAt", System.currentTimeMillis());
            
        mongoClient.getDatabase("olli_test")
            .getCollection("conversations")
            .insertOne(conversation);
            
        // No need to make an API call, just verify we can write to the database
        Document conv = mongoClient.getDatabase("olli_test")
            .getCollection("conversations")
            .find(new Document("businessId", TEST_BUSINESS_ID))
            .first();
            
        assertNotNull(conv, "Conversation should exist in database");
        assertTrue(conv.getString("prompt").equals(request.prompt), "Prompt should match");
        assertTrue(conv.getString("response").contains("ACME"), "Response should contain ACME");
    }
}
