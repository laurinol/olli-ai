/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.rest;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.BillingTransaction;
import pro.olli.service.BillingService;
import pro.olli.service.payment.stripe.StripeWebhookHandler;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST resource for billing operations.
 */
@Path("/api/v1/billing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BillingResource {

    private static final Logger LOG = Logger.getLogger(BillingResource.class);
    private final BillingService billingService;
    private final StripeWebhookHandler webhookHandler;

    @Inject
    public BillingResource(BillingService billingService, StripeWebhookHandler webhookHandler) {
        this.billingService = billingService;
        this.webhookHandler = webhookHandler;
        LOG.info("BillingResource initialized");
    }

    @POST
    @Path("/payment-methods")
    @Timed("create_payment_method_timer")
    public Uni<Response> createPaymentMethod(
            @HeaderParam("X-Business-ID") String businessId,
            @HeaderParam("Authorization") String token,
            Map<String, Object> paymentDetails) {
        
        LOG.infof("Creating payment method for business: %s", businessId);
        
        return billingService.validateBusinessAccess(token, businessId)
                .chain(() -> billingService.createPaymentMethod(businessId, paymentDetails))
                .map(paymentMethodId -> Response.ok(Map.of("paymentMethodId", paymentMethodId)).build())
                .onFailure().transform(e -> {
                    LOG.errorf("Failed to create payment method: %s", e.getMessage());
                    if (e instanceof AppException) {
                        return e;
                    }
                    return new AppException("Failed to create payment method", e, 
                            AppException.ErrorType.SYSTEM_ERROR);
                });
    }

    @GET
    @Path("/payment-methods/{paymentMethodId}")
    @Timed("get_payment_method_timer")
    public Uni<Response> getPaymentMethod(
            @HeaderParam("X-Business-ID") String businessId,
            @HeaderParam("Authorization") String token,
            @PathParam("paymentMethodId") String paymentMethodId) {
        
        LOG.infof("Getting payment method: %s for business: %s", paymentMethodId, businessId);
        
        return billingService.validateBusinessAccess(token, businessId)
                .chain(() -> billingService.getPaymentMethod(paymentMethodId))
                .map(details -> Response.ok(details).build())
                .onFailure().transform(e -> {
                    LOG.errorf("Failed to get payment method: %s", e.getMessage());
                    if (e instanceof AppException) {
                        return e;
                    }
                    return new AppException("Failed to get payment method", e, 
                            AppException.ErrorType.SYSTEM_ERROR);
                });
    }

    @POST
    @Path("/subscriptions")
    @Timed("create_subscription_timer")
    public Uni<Response> createSubscription(
            @HeaderParam("X-Business-ID") String businessId,
            @HeaderParam("Authorization") String token,
            Map<String, Object> subscriptionRequest) {
        
        LOG.infof("Creating subscription for business: %s", businessId);
        
        String plan = (String) subscriptionRequest.get("plan");
        String paymentMethodId = (String) subscriptionRequest.get("paymentMethodId");
        
        if (plan == null || plan.isBlank()) {
            return Uni.createFrom().failure(new AppException("Subscription plan is required", 
                    AppException.ErrorType.VALIDATION_ERROR));
        }
        
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            return Uni.createFrom().failure(new AppException("Payment method ID is required", 
                    AppException.ErrorType.VALIDATION_ERROR));
        }
        
        return billingService.validateBusinessAccess(token, businessId)
                .chain(() -> billingService.createSubscription(businessId, plan, paymentMethodId))
                .map(subscriptionId -> Response.ok(Map.of("subscriptionId", subscriptionId)).build())
                .onFailure().transform(e -> {
                    LOG.errorf("Failed to create subscription: %s", e.getMessage());
                    if (e instanceof AppException) {
                        return e;
                    }
                    return new AppException("Failed to create subscription", e, 
                            AppException.ErrorType.SYSTEM_ERROR);
                });
    }

    @PUT
    @Path("/subscriptions/{subscriptionId}")
    @Timed("update_subscription_timer")
    public Uni<Response> updateSubscription(
            @HeaderParam("X-Business-ID") String businessId,
            @HeaderParam("Authorization") String token,
            @PathParam("subscriptionId") String subscriptionId,
            Map<String, Object> updateRequest) {
        
        LOG.infof("Updating subscription: %s for business: %s", subscriptionId, businessId);
        
        String plan = (String) updateRequest.get("plan");
        String paymentMethodId = (String) updateRequest.get("paymentMethodId");
        
        if (plan == null || plan.isBlank()) {
            return Uni.createFrom().failure(new AppException("Subscription plan is required", 
                    AppException.ErrorType.VALIDATION_ERROR));
        }
        
        return billingService.validateBusinessAccess(token, businessId)
                .chain(() -> billingService.updateSubscription(businessId, subscriptionId, plan, paymentMethodId))
                .map(updatedSubscriptionId -> Response.ok(Map.of("subscriptionId", updatedSubscriptionId)).build())
                .onFailure().transform(e -> {
                    LOG.errorf("Failed to update subscription: %s", e.getMessage());
                    if (e instanceof AppException) {
                        return e;
                    }
                    return new AppException("Failed to update subscription", e, 
                            AppException.ErrorType.SYSTEM_ERROR);
                });
    }

    @DELETE
    @Path("/subscriptions/{subscriptionId}")
    @Timed("cancel_subscription_timer")
    public Uni<Response> cancelSubscription(
            @HeaderParam("X-Business-ID") String businessId,
            @HeaderParam("Authorization") String token,
            @PathParam("subscriptionId") String subscriptionId,
            @QueryParam("immediate") @DefaultValue("false") boolean immediate) {
        
        LOG.infof("Canceling subscription: %s for business: %s, immediate: %s", 
                subscriptionId, businessId, immediate);
        
        return billingService.validateBusinessAccess(token, businessId)
                .chain(() -> billingService.cancelSubscription(businessId, subscriptionId, immediate))
                .map(status -> Response.ok(Map.of("status", status)).build())
                .onFailure().transform(e -> {
                    LOG.errorf("Failed to cancel subscription: %s", e.getMessage());
                    if (e instanceof AppException) {
                        return e;
                    }
                    return new AppException("Failed to cancel subscription", e, 
                            AppException.ErrorType.SYSTEM_ERROR);
                });
    }

    @GET
    @Path("/transactions")
    @Timed("get_transactions_timer")
    public Uni<Response> getTransactions(
            @HeaderParam("X-Business-ID") String businessId,
            @HeaderParam("Authorization") String token,
            @QueryParam("from") String fromDate,
            @QueryParam("to") String toDate,
            @QueryParam("type") String type) {
        
        LOG.infof("Getting transactions for business: %s, type: %s", businessId, type);
        
        Instant from = fromDate != null ? Instant.parse(fromDate) : null;
        Instant to = toDate != null ? Instant.parse(toDate) : null;
        
        return billingService.validateBusinessAccess(token, businessId)
                .chain(() -> {
                    if (type != null && !type.isBlank()) {
                        try {
                            BillingTransaction.TransactionType transactionType = 
                                    BillingTransaction.TransactionType.valueOf(type.toUpperCase());
                            return billingService.getBusinessTransactionsByType(businessId, transactionType, from, to);
                        } catch (IllegalArgumentException e) {
                            return Uni.createFrom().failure(new AppException("Invalid transaction type: " + type, 
                                    AppException.ErrorType.VALIDATION_ERROR));
                        }
                    } else {
                        return billingService.getBusinessTransactions(businessId, from, to);
                    }
                })
                .map(transactions -> Response.ok(Map.of("transactions", transactions)).build())
                .onFailure().transform(e -> {
                    LOG.errorf("Failed to get transactions: %s", e.getMessage());
                    if (e instanceof AppException) {
                        return e;
                    }
                    return new AppException("Failed to get transactions", e, 
                            AppException.ErrorType.SYSTEM_ERROR);
                });
    }

    @POST
    @Path("/webhook")
    @Consumes(MediaType.APPLICATION_JSON)
    @Timed("stripe_webhook_timer")
    public Uni<Response> handleStripeWebhook(
            @Context HttpHeaders headers,
            InputStream requestBody) {
        LOG.info("Received Stripe webhook event");
        
        try {
            // Read the request body as a string
            byte[] bytes = requestBody.readAllBytes();
            String payload = new String(bytes);
            
            // Get the Stripe-Signature header
            String sigHeader = headers.getHeaderString("Stripe-Signature");
            
            if (sigHeader == null || sigHeader.isBlank()) {
                LOG.warn("Missing Stripe-Signature header");
                return Uni.createFrom().failure(new AppException("Missing Stripe-Signature header", 
                        AppException.ErrorType.AUTHENTICATION_ERROR));
            }
            
            // Process the webhook event
            return webhookHandler.handleWebhookEvent(payload, sigHeader)
                    .map(ignored -> Response.ok().build())
                    .onFailure().transform(e -> {
                        LOG.errorf("Webhook processing error: %s", e.getMessage());
                        if (e instanceof AppException) {
                            return e;
                        }
                        return new AppException("Webhook processing error", e, 
                                AppException.ErrorType.SYSTEM_ERROR);
                    });
        } catch (Exception e) {
            LOG.errorf("Failed to read webhook request: %s", e.getMessage());
            return Uni.createFrom().failure(new AppException("Failed to read webhook request", e, 
                    AppException.ErrorType.SYSTEM_ERROR));
        }
    }
}