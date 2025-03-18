/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import pro.olli.models.ChatRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost:8080";
    }

    @Test
    @Order(1)
    void testRegisterBusiness() {
        // In a real integration test, we would need an API endpoint to create an invite
        // For this test, we'll create an invite via the REST API
        
        // First, we need to create a business with an admin user
        Map<String, String> inviteRequest = new HashMap<>();
        inviteRequest.put("email", TEST_EMAIL);
        inviteRequest.put("businessId", TEST_BUSINESS_ID);
        
        given()
            .contentType(ContentType.JSON)
            .body(inviteRequest)
            .when()
            .post("/auth/invite")
            .then()
            .statusCode(200)
            .body("message", is("Invite sent"));
            
        // For testing purposes, we'd need a way to retrieve the token
        // In a real scenario, we'd check the email or have a test endpoint
        // Here we'll assume there's a test endpoint to get the latest invite token
        
        registrationToken = given()
            .queryParam("email", TEST_EMAIL)
            .when()
            .get("/test-integration/get-latest-invite")
            .then()
            .statusCode(200)
            .extract()
            .asString();
            
        // Register using the token
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("email", TEST_EMAIL);
        requestBody.put("password", TEST_PASSWORD);
        requestBody.put("token", registrationToken);

        authToken = given()
            .contentType(ContentType.JSON)
            .body(requestBody)
            .when()
            .post("/auth/register")
            .then()
            .statusCode(200)
            .header("Authorization", notNullValue())
            .extract()
            .header("Authorization")
            .replace("Bearer ", "");

        assertNotNull(authToken, "Auth token should be returned");
    }

    @Test
    @Order(2)
    void testVerifyBusinessCreated() {
        // Verify the business was created by checking user info via API
        // In a real integration test, we could have a /user/profile endpoint
        given()
            .header("Authorization", "Bearer " + authToken)
            .when()
            .get("/auth/profile")
            .then()
            .statusCode(200)
            .body("businessId", is(TEST_BUSINESS_ID))
            .body("email", is(TEST_EMAIL));
    }

    @Test
    @Order(3)
    void testAddLocalDirectoryForSync() {
        // Add local directory for scanning
        String syncResponse = given()
            .header("Authorization", "Bearer " + authToken)
            .when()
            .get("/auth/sync/local?folderId=test-files&businessId=" + TEST_BUSINESS_ID)
            .then()
            .statusCode(200)
            .body("message", is("Sync queued"))
            .body("taskId", notNullValue())
            .extract()
            .path("taskId");

        syncTaskId = syncResponse;
        assertNotNull(syncTaskId, "Sync task ID should be returned");
    }

    @Test
    @Order(4)
    void testTaskAddedToQueue() {
        // In a real integration test, we would need an API to check queue status
        // Here we'll assume there's a test endpoint for this
        
        given()
            .queryParam("taskId", syncTaskId)
            .when()
            .get("/test-integration/check-queue-task")
            .then()
            .statusCode(200)
            .body("exists", is(true))
            .body("businessId", is(TEST_BUSINESS_ID))
            .body("source", is("local"));
    }

    @Test
    @Order(5)
    void testSyncWorkerProcessesFiles() {
        // In a real integration test, we would need a way to force processing or wait
        // Here we'll use a test endpoint to trigger task processing

        given()
            .queryParam("taskId", syncTaskId)
            .when()
            .post("/test-integration/process-sync-task")
            .then()
            .statusCode(200)
            .body("processed", is(true));

        // Wait a moment for processing to complete
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify files were processed
        given()
            .header("Authorization", "Bearer " + authToken)
            .when()
            .get("/auth/sync/status?taskId=" + syncTaskId)
            .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("fileCount", org.hamcrest.Matchers.greaterThan(0));
    }

    @Test
    @Order(6)
    void testChatQuery() {
        // Create a chat request
        ChatRequest request = new ChatRequest();
        request.prompt = "What's in the contract with ACME?";
        request.history = new ArrayList<>();

        // Send the chat request
        given()
            .header("Authorization", "Bearer " + authToken)
            .contentType(ContentType.JSON)
            .body(request)
            .when()
            .post("/chat/query")
            .then()
            .statusCode(200)
            .body("response", notNullValue())
            .body("response", anyOf(
                containsString("ACME"),
                containsString("contract"),
                containsString("agreement")
            ));
    }
}
