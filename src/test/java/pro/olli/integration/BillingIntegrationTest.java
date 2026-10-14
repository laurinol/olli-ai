/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(BillingTestProfile.class)
@TestMethodOrder(OrderAnnotation.class)
@Tag("integration")
public class BillingIntegrationTest {

    private static final String API_BASE = "/test-integration/billing";
    private static String businessId;
    private static String userId;
    private static String paymentMethodId;
    private static String paymentIntentId;
    private static String subscriptionId;
    
    @Test
    @Order(1)
    public void testSetupTestUser() {
        // Create test business and user IDs
        businessId = "business-" + UUID.randomUUID().toString();
        userId = "user-" + UUID.randomUUID().toString();
        
        // Register test user and business in the system
        Map<String, Object> user = new HashMap<>();
        user.put("id", userId);
        user.put("email", "test-" + UUID.randomUUID().toString() + "@example.com");
        user.put("name", "Test User");
        
        Map<String, Object> business = new HashMap<>();
        business.put("id", businessId);
        business.put("name", "Test Business");
        business.put("userId", userId);
        
        System.out.println("------------ TESTING WITH PATH /test-integration/health");
        
        // Test if TestResource is loaded and accessible
        Response healthResponse = given()
                .when()
                .get("/test-integration/health")
                .then()
                .extract().response();
        
        System.out.println("Status code: " + healthResponse.getStatusCode());
        System.out.println("Response body: " + healthResponse.getBody().asString());
                
        // Create the business
        Response response = given()
                .contentType(ContentType.JSON)
                .body(business)
                .when()
                .post("/test/create-business") // Test endpoint for creating test data
                .then()
                .statusCode(200)
                .body("id", equalTo(businessId))
                .extract().response();
                
        // Save the business ID for later tests
        assertNotNull(response.jsonPath().getString("id"));
    }
    
    @Test
    @Order(2)
    public void testCreatePaymentMethod() {
        // Create a test payment method
        Map<String, Object> paymentMethod = new HashMap<>();
        paymentMethod.put("cardNumber", "4242424242424242"); // Test card number
        paymentMethod.put("expiryMonth", 12);
        paymentMethod.put("expiryYear", 2030);
        paymentMethod.put("cvc", "123");
        
        Response response = given()
                .contentType(ContentType.JSON)
                .body(paymentMethod)
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .post(API_BASE + "/payment-methods")
                .then()
                .statusCode(200)
                .body("id", startsWith("pm_"))
                .extract().response();
                
        // Save the payment method ID for later tests
        paymentMethodId = response.jsonPath().getString("id");
        assertNotNull(paymentMethodId);
    }
    
    @Test
    @Order(3)
    public void testCreatePaymentIntent() {
        // Create a payment intent for $10.00
        Map<String, Object> paymentRequest = new HashMap<>();
        paymentRequest.put("amount", 1000); // $10.00 in cents
        paymentRequest.put("currency", "usd");
        paymentRequest.put("description", "Test payment");
        paymentRequest.put("paymentMethodId", paymentMethodId);
        
        Response response = given()
                .contentType(ContentType.JSON)
                .body(paymentRequest)
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .post(API_BASE + "/payment")
                .then()
                .statusCode(200)
                .body("id", startsWith("pi_"))
                .body("clientSecret", notNullValue())
                .extract().response();
                
        // Save the payment intent ID for later tests
        paymentIntentId = response.jsonPath().getString("id");
        assertNotNull(paymentIntentId);
    }
    
    @Test
    @Order(4)
    public void testGetTransaction() {
        // Check that the transaction was created for the payment intent
        given()
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .get(API_BASE + "/transactions")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].externalId", equalTo(paymentIntentId))
                .body("[0].status", equalTo("PENDING"));
    }
    
    @Test
    @Order(5)
    public void testCreateSubscription() {
        // Create a subscription to the PRO plan
        Map<String, Object> subscriptionRequest = new HashMap<>();
        subscriptionRequest.put("plan", "PRO");
        subscriptionRequest.put("paymentMethodId", paymentMethodId);
        
        Response response = given()
                .contentType(ContentType.JSON)
                .body(subscriptionRequest)
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .post(API_BASE + "/subscribe")
                .then()
                .statusCode(200)
                .body("id", startsWith("sub_"))
                .body("status", equalTo("active"))
                .extract().response();
                
        // Save the subscription ID for later tests
        subscriptionId = response.jsonPath().getString("id");
        assertNotNull(subscriptionId);
    }
    
    @Test
    @Order(6)
    public void testGetSubscription() {
        // Check that the business was updated with the subscription
        given()
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .get(API_BASE + "/subscription")
                .then()
                .statusCode(200)
                .body("plan", equalTo("PRO"))
                .body("status", equalTo("active"))
                .body("subscriptionId", equalTo(subscriptionId));
    }
    
    @Test
    @Order(7)
    public void testUpdateSubscription() {
        // Upgrade to ENTERPRISE plan
        Map<String, Object> updateRequest = new HashMap<>();
        updateRequest.put("plan", "ENTERPRISE");
        
        given()
                .contentType(ContentType.JSON)
                .body(updateRequest)
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .put(API_BASE + "/subscription")
                .then()
                .statusCode(200)
                .body("plan", equalTo("ENTERPRISE"))
                .body("status", equalTo("active"));
    }
    
    @Test
    @Order(8)
    public void testCancelSubscription() {
        // Cancel subscription
        given()
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .delete(API_BASE + "/subscription")
                .then()
                .statusCode(200)
                .body("status", equalTo("canceling"));
    }
    
    @Test
    @Order(9)
    public void testWebhookHandling() {
        // Simulate webhook for payment_intent.succeeded
        String paymentIntentPayload = String.format(
                "{"
                + "\"id\": \"evt_%s\","
                + "\"object\": \"event\","
                + "\"type\": \"payment_intent.succeeded\","
                + "\"data\": {"
                + "  \"object\": {"
                + "    \"id\": \"%s\","
                + "    \"object\": \"payment_intent\","
                + "    \"status\": \"succeeded\""
                + "  }"
                + "}"
                + "}",
                UUID.randomUUID().toString().replace("-", ""),
                paymentIntentId
        );
        
        // Create a mock signature header
        String signature = "t=1234567890,v1=mock_signature";
        
        // Send the webhook
        given()
                .contentType(ContentType.JSON)
                .header("Stripe-Signature", signature)
                .body(paymentIntentPayload)
                .when()
                .post(API_BASE + "/webhook")
                .then()
                .statusCode(200);
        
        // Verify that the transaction status was updated to COMPLETED
        // Note: In a real test, we would need to wait for the async processing to complete
        // or mock the webhook handler to be synchronous
    }
    
    @Test
    @Order(10)
    public void testCleanup() {
        // Clean up test data
        given()
                .header("X-User-Id", userId)
                .header("X-Business-Id", businessId)
                .when()
                .delete("/test/cleanup-business") // Test endpoint for cleaning up test data
                .then()
                .statusCode(204);
    }
}
