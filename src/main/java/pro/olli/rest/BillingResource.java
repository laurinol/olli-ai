/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.rest;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;
import pro.olli.models.SubscriptionPlan;
import pro.olli.service.BillingService;

import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for billing operations.
 */
@Path("/billing")
public class BillingResource {
    
    private static final Logger LOG = Logger.getLogger(BillingResource.class);
    
    private final BillingService billingService;
    
    @Inject
    public BillingResource(BillingService billingService) {
        this.billingService = billingService;
    }
    
    /**
     * Get subscription plans
     */
    @GET
    @Path("/plans")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSubscriptionPlans() {
        List<Map<String, Object>> plans = List.of(
            Map.of(
                "id", SubscriptionPlan.FREE.getValue(),
                "name", "Free",
                "features", List.of(
                    "5GB Storage",
                    "500 documents/month",
                    "50 queries/month",
                    "Basic features"
                ),
                "priceInCents", SubscriptionPlan.FREE.getPriceInCents(),
                "priceDisplay", "$0/month"
            ),
            Map.of(
                "id", SubscriptionPlan.BASIC.getValue(),
                "name", "Basic",
                "features", List.of(
                    "10GB Storage",
                    "1,000 documents/month",
                    "200 queries/month",
                    "Basic features"
                ),
                "priceInCents", SubscriptionPlan.BASIC.getPriceInCents(),
                "priceDisplay", "$99/month"
            ),
            Map.of(
                "id", SubscriptionPlan.PRO.getValue(),
                "name", "Professional",
                "features", List.of(
                    "50GB Storage",
                    "5,000 documents/month",
                    "1,000 queries/month",
                    "Advanced features",
                    "Priority support"
                ),
                "priceInCents", SubscriptionPlan.PRO.getPriceInCents(),
                "priceDisplay", "$399/month"
            ),
            Map.of(
                "id", SubscriptionPlan.ENTERPRISE.getValue(),
                "name", "Enterprise",
                "features", List.of(
                    "200GB Storage",
                    "20,000 documents/month",
                    "5,000 queries/month",
                    "Advanced features",
                    "Dedicated support",
                    "Custom integrations"
                ),
                "priceInCents", SubscriptionPlan.ENTERPRISE.getPriceInCents(),
                "priceDisplay", "$1,499/month"
            )
        );
        
        return Response.ok(plans).build();
    }
    
    /**
     * Get business usage metrics
     */
    @GET
    @Path("/usage")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user", "admin"})
    public Uni<Response> getUsageMetrics(
            @QueryParam("businessId") String businessId,
            @Context SecurityContext securityContext) {
        
        Principal principal = securityContext.getUserPrincipal();
        if (principal == null) {
            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED).build());
        }
        
        return billingService.getBusinessUsageMetrics(businessId)
            .map(metrics -> Response.ok(metrics).build())
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to get usage metrics: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
            });
    }
    
    /**
     * Get billing history
     */
    @GET
    @Path("/history")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"user", "admin"})
    public Uni<Response> getBillingHistory(
            @QueryParam("businessId") String businessId,
            @QueryParam("months") Integer months,
            @Context SecurityContext securityContext) {
        
        Principal principal = securityContext.getUserPrincipal();
        if (principal == null) {
            return Uni.createFrom().item(Response.status(Response.Status.UNAUTHORIZED).build());
        }
        
        // Default to 3 months
        int lookbackMonths = months != null ? months : 3;
        Instant fromDate = Instant.now().minus(lookbackMonths, ChronoUnit.MONTHS);
        
        return billingService.getBillingHistory(businessId, fromDate, Instant.now())
            .map(transactions -> Response.ok(transactions).build())
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to get billing history: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
            });
    }
    
    /**
     * Update subscription plan
     */
    @POST
    @Path("/update-plan")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin"})
    public Uni<Response> updateSubscriptionPlan(UpdateSubscriptionRequest request) {
        return billingService.updateSubscriptionPlan(
                request.businessId, 
                SubscriptionPlan.fromString(request.plan),
                request.paymentMethodId)
            .map(business -> Response.ok(Map.of(
                    "message", "Subscription updated successfully",
                    "plan", business.getSubscriptionPlan(),
                    "status", business.getSubscriptionStatus(),
                    "nextBillingDate", business.getNextBillingDate()
                )).build())
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Failed to update subscription: %s", e.getMessage());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
            });
    }
    
    public static class UpdateSubscriptionRequest {
        public String businessId;
        public String plan;
        public String paymentMethodId;
    }
}