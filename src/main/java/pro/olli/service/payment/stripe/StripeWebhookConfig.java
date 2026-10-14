/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.service.payment.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.WebhookEndpoint;
import com.stripe.param.WebhookEndpointCreateParams;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration class for Stripe webhooks.
 * This class can be used to register webhook endpoints with Stripe during application startup.
 */
@ApplicationScoped
public class StripeWebhookConfig {

    private static final Logger LOG = Logger.getLogger(StripeWebhookConfig.class);
    
    private final String stripeApiKey;
    private final String stripeWebhookUrl;
    private final boolean autoRegisterWebhooks;
    
    /**
     * List of events that our application handles.
     */
    private static final List<String> REQUIRED_EVENTS = Arrays.asList(
            "payment_intent.succeeded",
            "payment_intent.payment_failed",
            "invoice.payment_succeeded",
            "invoice.payment_failed",
            "customer.subscription.updated",
            "customer.subscription.deleted"
    );
    
    @Inject
    public StripeWebhookConfig(
            @ConfigProperty(name = "stripe.api.key", defaultValue = "") String stripeApiKey,
            @ConfigProperty(name = "stripe.webhook.url", defaultValue = "") String stripeWebhookUrl,
            @ConfigProperty(name = "stripe.webhook.auto-register", defaultValue = "false") boolean autoRegisterWebhooks) {
        this.stripeApiKey = stripeApiKey;
        this.stripeWebhookUrl = stripeWebhookUrl;
        this.autoRegisterWebhooks = autoRegisterWebhooks;
        
        if (!stripeApiKey.isEmpty()) {
            Stripe.apiKey = stripeApiKey;
            LOG.infof("Stripe API key configured: %s****", 
                    stripeApiKey.substring(0, Math.min(4, stripeApiKey.length())));
        } else {
            LOG.warn("No Stripe API key configured");
        }
        
        if (autoRegisterWebhooks) {
            if (stripeWebhookUrl.isEmpty()) {
                LOG.warn("Auto-register webhooks is enabled but no webhook URL is configured");
            } else {
                LOG.infof("Auto-register webhooks is enabled with URL: %s", stripeWebhookUrl);
            }
        }
    }
    
    /**
     * Initialize the Stripe webhook configuration.
     * This method will be called on application startup if @Startup is uncommented.
     */
    // @Startup - Uncomment when ready to use in production
    void init() {
        if (autoRegisterWebhooks && !stripeApiKey.isEmpty() && !stripeWebhookUrl.isEmpty()) {
            try {
                registerWebhook();
            } catch (Exception e) {
                LOG.errorf("Failed to register Stripe webhook: %s", e.getMessage());
            }
        }
    }
    
    /**
     * Register a webhook endpoint with Stripe.
     * This method will check if a webhook endpoint already exists for the configured URL
     * and create a new one if needed.
     * 
     * @return The webhook secret key for signature verification
     * @throws StripeException if an error occurs with the Stripe API
     */
    public String registerWebhook() throws StripeException {
        LOG.info("Registering Stripe webhook endpoint");
        
        // Check if webhook already exists
        Optional<WebhookEndpoint> existingEndpoint = findExistingWebhook();
        if (existingEndpoint.isPresent()) {
            LOG.infof("Webhook endpoint already exists for URL %s", stripeWebhookUrl);
            // Note: We can't retrieve the webhook secret from an existing endpoint
            // The secret is only provided when the endpoint is created
            return "EXISTING_WEBHOOK_SECRET_NOT_AVAILABLE";
        }
        
        // Create new webhook endpoint
        WebhookEndpointCreateParams.Builder paramsBuilder = WebhookEndpointCreateParams.builder()
                .setUrl(stripeWebhookUrl)
                .setDescription("Olli AI webhook endpoint");
                
        // Add each required event
        for (String event : REQUIRED_EVENTS) {
            paramsBuilder.addEnabledEvent(WebhookEndpointCreateParams.EnabledEvent.valueOf(event.toUpperCase().replace(".", "_")));
        }
        
        WebhookEndpointCreateParams params = paramsBuilder.build();
        
        WebhookEndpoint endpoint = WebhookEndpoint.create(params);
        LOG.infof("Created Stripe webhook endpoint with ID %s", endpoint.getId());
        
        // Return the webhook secret
        return endpoint.getSecret();
    }
    
    /**
     * Find an existing webhook endpoint for the configured URL.
     * 
     * @return Optional containing the webhook endpoint if found
     * @throws StripeException if an error occurs with the Stripe API
     */
    private Optional<WebhookEndpoint> findExistingWebhook() throws StripeException {
        LOG.infof("Checking for existing webhook endpoints matching URL: %s", stripeWebhookUrl);
        
        Iterable<WebhookEndpoint> endpoints = WebhookEndpoint.list((Map<String, Object>)null).getData();
        for (WebhookEndpoint endpoint : endpoints) {
            if (stripeWebhookUrl.equals(endpoint.getUrl())) {
                LOG.infof("Found matching webhook endpoint with ID: %s", endpoint.getId());
                return Optional.of(endpoint);
            }
        }
        
        LOG.info("No matching webhook endpoint found");
        return Optional.empty();
    }
}
