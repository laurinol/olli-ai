/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.integration;

import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test resource for billing integration tests.
 * This implementation only exists in the test classpath.
 */
@Path("/")
@ApplicationScoped
public class BillingTestResource {

    private static final Logger LOG = Logger.getLogger(BillingTestResource.class);

    @Inject
    Gson gson;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "olli_test")
    String databaseName;

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
}
