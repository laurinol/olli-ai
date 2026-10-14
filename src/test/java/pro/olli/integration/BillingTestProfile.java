/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Test profile for billing integration tests.
 * This profile sets up properties for testing with Stripe in test mode.
 */
public class BillingTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();
        
        // Enable billing
        config.put("billing.enabled", "true");
        config.put("payment.provider", "stripe");
        
        // Stripe test mode configuration
        config.put("stripe.api.key", "sk_test_example123secretkey");
        config.put("stripe.webhook.secret", "whsec_example123webhooksecret");
        
        // Stripe price IDs for test mode
        config.put("stripe.price.basic", "price_basic123");
        config.put("stripe.price.professional", "price_professional123");
        config.put("stripe.price.enterprise", "price_enterprise123");
        
        // Use MongoDB for tests
        config.put("database.provider", "mongodb");
        config.put("quarkus.mongodb.connection-string", "mongodb://admin:password@localhost:27017");
        config.put("quarkus.mongodb.database", "olli_test");
        
        return config;
    }

    @Override
    public String getConfigProfile() {
        return "test";
    }
}
