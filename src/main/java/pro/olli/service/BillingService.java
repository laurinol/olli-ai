/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.service;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.gson.Gson;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.config.AppConfig;
import pro.olli.models.Business;
import pro.olli.models.SubscriptionPlan;
import pro.olli.models.BillingTransaction;
import pro.olli.repository.BillingRepository;
import pro.olli.repository.UserRepository;
import pro.olli.repository.Factory;
import pro.olli.service.payment.PaymentProvider;
import pro.olli.service.payment.PaymentProviderFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for handling billing operations.
 * This is a simplified version that stores usage metrics directly in the Business document.
 */
@ApplicationScoped
public class BillingService {

    private static final Logger LOG = Logger.getLogger(BillingService.class);

    private final AppConfig config;
    private final UserRepository userRepository;
    private final BillingRepository billingRepository;
    private final PaymentProvider paymentProvider;
    private final Firestore firestore;

    @Inject
    public BillingService(
            AppConfig config,
            UserRepository userRepository,
            BillingRepository billingRepository,
            PaymentProviderFactory paymentProviderFactory,
            Firestore firestore) {
        this.config = config;
        this.userRepository = userRepository;
        this.billingRepository = billingRepository;
        this.paymentProvider = paymentProviderFactory.getPaymentProvider();
        this.firestore = firestore;

        LOG.infof("BillingService initialized with billing enabled: %s", config.isBillingEnabled());
    }

    /**
     * Scheduler job that runs on the first day of each month to reset usage metrics
     * and charge subscriptions.
     */
    @io.quarkus.scheduler.Scheduled(cron = "0 0 0 1 * ?")
    void monthlyBillingJob() {
        if (!config.isBillingEnabled()) {
            LOG.info("Billing is disabled, skipping monthly billing job");
            return;
        }

        LOG.info("Running monthly billing job to reset usage metrics and charge subscriptions");

        // Get all businesses and reset their usage metrics
        Uni.createFrom().future(firestore.collection("clients").get())
            .subscribe().with(
                querySnapshot -> {
                    LOG.infof("Processing %d businesses for monthly billing", querySnapshot.size());

                    for (var docSnapshot : querySnapshot.getDocuments()) {
                        try {
                            Business business = mapToBusiness(docSnapshot);
                            if (business == null) {
                                LOG.warnf("Failed to map business from document: %s", docSnapshot.getId());
                                continue;
                            }

                            // Reset usage counters
                            Business updatedBusiness = business.withUsageMetrics(
                                business.getCurrentConnectorUsageBytes(), // Keep connector usage
                                0, // Reset documents processed
                                0  // Reset queries
                            );

                            // Save updated business
                            userRepository.saveBusiness(updatedBusiness)
                                .subscribe().with(
                                    ignored -> LOG.infof("Reset usage metrics for business: %s", business.getId()),
                                    e -> LOG.errorf("Failed to reset usage metrics for business %s: %s",
                                                    business.getId(), e.getMessage())
                                );

                            // TODO: Process payment for subscription (would use Stripe or another payment processor)
                            LOG.infof("Would charge business %s $%.2f for %s plan",
                                    business.getId(),
                                    business.getSubscriptionPlanEnum().getPriceInCents() / 100.0,
                                    business.getSubscriptionPlanEnum().name());
                        } catch (Exception e) {
                            LOG.errorf("Error processing business %s for monthly billing: %s",
                                       docSnapshot.getId(), e.getMessage());
                        }
                    }
                },
                e -> LOG.errorf("Failed to list businesses for monthly billing: %s", e.getMessage())
            );
    }

    /**
     * Creates a new business with free trial
     */
    public Uni<Business> createBusinessWithTrial(String businessId, String name) {
        LOG.infof("Creating business with free trial: %s (%s)", name, businessId);

        Business business = new Business(businessId, name, SubscriptionPlan.FREE.getValue());
        return userRepository.saveBusiness(business)
            .map(v -> business); // Return the business after saving it
    }

    /**
     * Updates business subscription plan
     */
    public Uni<Business> updateSubscriptionPlan(String businessId, SubscriptionPlan newPlan, String paymentMethodId) {
        LOG.infof("Updating subscription plan for business %s to %s", businessId, newPlan.getValue());

        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Business not found"));
                }

                Business business = businessOpt.get();

                // Update business with new plan
                Business updatedBusiness = business
                    .withSubscriptionPlan(newPlan.getValue())
                    .withPaymentMethodId(paymentMethodId);

                return userRepository.saveBusiness(updatedBusiness).map(v -> updatedBusiness);
            });
    }

    /**
     * Record document processing usage for a business
     */
    public Uni<Void> recordDocumentProcessing(String businessId, int documentCount) {
        if (!config.isBillingEnabled()) {
            return Uni.createFrom().voidItem();
        }

        LOG.debugf("Recording document processing: %d for business %s", documentCount, businessId);

        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    LOG.warnf("Business %s not found for recording document processing", businessId);
                    return Uni.createFrom().voidItem();
                }

                Business business = businessOpt.get();

                // Check if over limit
                if (business.isDocumentLimitExceeded()) {
                    LOG.warnf("Business %s has exceeded document processing limit", businessId);
                    return Uni.createFrom().voidItem();
                }

                // Update document count
                long newCount = Math.min(
                    business.getDocumentsProcessedThisMonth() + documentCount,
                    business.getSubscriptionPlanEnum().getMonthlyDocumentLimit()
                );

                Business updatedBusiness = business.incrementDocumentsProcessed(documentCount);
                return userRepository.saveBusiness(updatedBusiness)
                    .onItem().ignore().andContinueWithNull();
            })
            .onFailure().invoke(e ->
                LOG.errorf("Failed to record document processing for business %s: %s",
                    businessId, e.getMessage())
            )
            .onFailure().recoverWithNull(); // Continue even if there's an error
    }

    /**
     * Record query usage for a business
     */
    public Uni<Void> recordQueryUsage(String businessId) {
        if (!config.isBillingEnabled()) {
            return Uni.createFrom().voidItem();
        }

        LOG.debugf("Recording query usage for business %s", businessId);

        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    LOG.warnf("Business %s not found for recording query usage", businessId);
                    return Uni.createFrom().voidItem();
                }

                Business business = businessOpt.get();

                // Check if over limit
                if (business.isQueryLimitExceeded()) {
                    LOG.warnf("Business %s has exceeded query limit", businessId);
                    return Uni.createFrom().voidItem();
                }

                // Increment query count
                Business updatedBusiness = business.incrementQueries(1);
                return userRepository.saveBusiness(updatedBusiness)
                    .onItem().ignore().andContinueWithNull();
            })
            .onFailure().invoke(e ->
                LOG.errorf("Failed to record query usage for business %s: %s",
                    businessId, e.getMessage())
            )
            .onFailure().recoverWithNull(); // Continue even if there's an error
    }

    /**
     * Update connector usage for a business
     */
    public Uni<Void> updateConnectorUsage(String businessId, int connectorBytes) {
        if (!config.isBillingEnabled()) {
            return Uni.createFrom().voidItem();
        }

        LOG.debugf("Updating connector usage: %d bytes for business %s", connectorBytes, businessId);

        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    LOG.warnf("Business %s not found for updating connector usage", businessId);
                    return Uni.createFrom().voidItem();
                }

                Business business = businessOpt.get();

                // Update connector usage
                Business updatedBusiness = business.withUsageMetrics(
                    connectorBytes,
                    business.getDocumentsProcessedThisMonth(),
                    business.getQueriesThisMonth()
                );

                return userRepository.saveBusiness(updatedBusiness)
                    .onItem().ignore().andContinueWithNull();
            })
            .onFailure().invoke(e ->
                LOG.errorf("Failed to update connector usage for business %s: %s",
                    businessId, e.getMessage())
            )
            .onFailure().recoverWithNull(); // Continue even if there's an error
    }

    /**
     * Check if a business is within its subscription limits
     */
    public Uni<Business> checkBusinessLimits(String businessId) {
        if (!config.isBillingEnabled()) {
            // If billing is disabled, create a dummy business with unlimited quota
            Business unlimitedBusiness = new Business(
                businessId,
                "Unlimited",
                SubscriptionPlan.ENTERPRISE.getValue()
            );
            return Uni.createFrom().item(unlimitedBusiness);
        }

        return userRepository.getBusiness(businessId)
            .map(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    LOG.warnf("Business %s not found when checking limits", businessId);
                    throw new IllegalArgumentException("Business not found");
                }

                Business business = businessOpt.get();

                // For simplified implementation, just log warnings but don't block
                if (!business.isSubscriptionActive()) {
                    LOG.warnf("Business %s has inactive subscription: %s",
                            businessId, business.getSubscriptionStatus());
                }

                return business;
            });
    }

    /**
     * Get business usage metrics
     */
    public Uni<Map<String, Object>> getBusinessUsageMetrics(String businessId) {
        return userRepository.getBusiness(businessId)
            .map(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    throw new IllegalArgumentException("Business not found");
                }

                Business business = businessOpt.get();
                SubscriptionPlan plan = business.getSubscriptionPlanEnum();

                Map<String, Object> metrics = new HashMap<>();

                // Subscription info
                metrics.put("plan", plan.getValue());
                metrics.put("planName", plan.name());
                metrics.put("status", business.getSubscriptionStatus());
                metrics.put("nextBillingDate", business.getNextBillingDate());

                // Connector usage
                double connectorUsedGB = business.getCurrentConnectorUsageGB();
                metrics.put("connectorUsedGB", connectorUsedGB);
                metrics.put("connectorLimitGB", plan.getConnectorLimitGB());
                metrics.put("connectorPercentUsed", (connectorUsedGB / plan.getConnectorLimitGB()) * 100);

                // Document processing usage
                metrics.put("documentsProcessed", business.getDocumentsProcessedThisMonth());
                metrics.put("documentLimit", plan.getMonthlyDocumentLimit());
                metrics.put("documentsPercentUsed",
                        ((double) business.getDocumentsProcessedThisMonth() / plan.getMonthlyDocumentLimit()) * 100);

                // Query usage
                metrics.put("queriesExecuted", business.getQueriesThisMonth());
                metrics.put("queryLimit", plan.getMonthlyQueryLimit());
                metrics.put("queriesPercentUsed",
                        ((double) business.getQueriesThisMonth() / plan.getMonthlyQueryLimit()) * 100);

                // Price info
                metrics.put("price", plan.getPriceInCents() / 100.0);
                metrics.put("priceDisplay", String.format("$%.2f/month", plan.getPriceInCents() / 100.0));

                return metrics;
            });
    }

    /**
     * Helper method to map Firestore document to Business object
     */
    private Business mapToBusiness(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) {
            return null;
        }

        Map<String, Object> data = doc.getData();
        if (data == null) {
            return null;
        }

        String id = (String) data.getOrDefault("id", doc.getId());
        String name = (String) data.get("name");

        // Get subscription data
        String subscriptionPlan = (String) data.get("subscriptionPlan");
        if (subscriptionPlan == null) {
            // Backwards compatibility - use planLevel if subscriptionPlan isn't set
            subscriptionPlan = (String) data.get("planLevel");
            // Default to FREE if nothing is set
            if (subscriptionPlan == null) {
                subscriptionPlan = "free";
            }
        }

        String subscriptionStatus = (String) data.get("subscriptionStatus");
        if (subscriptionStatus == null) {
            // Default to TRIAL if not set
            subscriptionStatus = "trial";
        }

        // Parse timestamps
        Instant createdAt = null;
        if (data.get("createdAt") instanceof Long) {
            createdAt = Instant.ofEpochMilli((Long) data.get("createdAt"));
        }

        Instant updatedAt = null;
        if (data.get("updatedAt") instanceof Long) {
            updatedAt = Instant.ofEpochMilli((Long) data.get("updatedAt"));
        }

        Instant subscriptionStartDate = null;
        if (data.get("subscriptionStartDate") instanceof Long) {
            subscriptionStartDate = Instant.ofEpochMilli((Long) data.get("subscriptionStartDate"));
        }

        Instant nextBillingDate = null;
        if (data.get("nextBillingDate") instanceof Long) {
            nextBillingDate = Instant.ofEpochMilli((Long) data.get("nextBillingDate"));
        }

        String paymentMethodId = (String) data.get("paymentMethodId");

        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) data.get("settings");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");

        // Get usage metrics
        int currentConnectorUsageBytes = 0;
        if (data.get("currentConnectorUsageBytes") instanceof Number) {
            currentConnectorUsageBytes = ((Number) data.get("currentConnectorUsageBytes")).intValue();
        }

        int documentsProcessedThisMonth = 0;
        if (data.get("documentsProcessedThisMonth") instanceof Number) {
            documentsProcessedThisMonth = ((Number) data.get("documentsProcessedThisMonth")).intValue();
        }

        int queriesThisMonth = 0;
        if (data.get("queriesThisMonth") instanceof Number) {
            queriesThisMonth = ((Number) data.get("queriesThisMonth")).intValue();
        }

        return new Business(id, name, subscriptionPlan,
                           subscriptionStatus,
                           subscriptionStartDate != null ? subscriptionStartDate : Instant.now(),
                           nextBillingDate != null ? nextBillingDate : Instant.now().plus(30, ChronoUnit.DAYS),
                           paymentMethodId,
                           createdAt != null ? createdAt : Instant.now(),
                           updatedAt != null ? updatedAt : Instant.now(),
                           settings, metadata,
                           currentConnectorUsageBytes, documentsProcessedThisMonth, queriesThisMonth);
    }

    /**
     * Get billing history for a business within a date range
     * This is a simplified implementation that creates placeholder transaction history
     * since we don't have actual transaction data yet.
     */
    public Uni<List<Map<String, Object>>> getBillingHistory(String businessId, Instant fromDate, Instant toDate) {
        if (!config.isBillingEnabled()) {
            return Uni.createFrom().item(Collections.emptyList());
        }

        LOG.infof("Getting billing history for business %s from %s to %s",
                 businessId, fromDate.toString(), toDate.toString());

        return userRepository.getBusiness(businessId)
            .map(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    throw new IllegalArgumentException("Business not found");
                }

                Business business = businessOpt.get();
                SubscriptionPlan plan = business.getSubscriptionPlanEnum();

                // For now, create a simulated transaction history
                // In a full implementation, this would fetch from the BillingRepository
                List<Map<String, Object>> transactions = new ArrayList<>();

                // Add current subscription payment
                Instant lastPaymentDate = business.getNextBillingDate().minus(30, ChronoUnit.DAYS);
                if (lastPaymentDate.isAfter(fromDate) && lastPaymentDate.isBefore(toDate)) {
                    transactions.add(Map.of(
                        "id", "txn_" + System.currentTimeMillis(),
                        "type", "SUBSCRIPTION_PAYMENT",
                        "status", "COMPLETED",
                        "amount", plan.getPriceInCents() / 100.0,
                        "amountDisplay", String.format("$%.2f", plan.getPriceInCents() / 100.0),
                        "description", plan.name() + " Plan - Monthly Subscription",
                        "timestamp", lastPaymentDate
                    ));
                }

                // Add plan change if applicable
                if (business.getSubscriptionStartDate().isAfter(fromDate) &&
                    business.getSubscriptionStartDate().isBefore(toDate)) {
                    transactions.add(Map.of(
                        "id", "txn_plan_" + System.currentTimeMillis(),
                        "type", "PLAN_CHANGE",
                        "status", "COMPLETED",
                        "amount", 0.0,
                        "amountDisplay", "$0.00",
                        "description", "Subscription changed to " + plan.name() + " Plan",
                        "timestamp", business.getSubscriptionStartDate()
                    ));
                }

                return transactions;
            });
    }

    /**
     * Validate that the user has access to the business.
     * This is a stub method for demonstration purposes.
     */
    public Uni<Void> validateBusinessAccess(String token, String businessId) {
        LOG.infof("Validating business access for token: %s, business: %s", token, businessId);
        // In a real implementation, this would verify the token and check if the user
        // has access to the specified business
        return Uni.createFrom().voidItem();
    }

    /**
     * Create a payment method for a business.
     */
    public Uni<String> createPaymentMethod(String businessId, Map<String, Object> paymentDetails) {
        LOG.infof("Creating payment method for business: %s", businessId);
        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Business not found"));
                }

                Business business = businessOpt.get();
                return paymentProvider.createPaymentMethod(businessId, paymentDetails);
            });
    }

    /**
     * Get payment method details.
     */
    public Uni<Map<String, Object>> getPaymentMethod(String paymentMethodId) {
        LOG.infof("Getting payment method: %s", paymentMethodId);
        return paymentProvider.getPaymentMethod(paymentMethodId);
    }

    /**
     * Create a subscription for a business.
     */
    public Uni<String> createSubscription(String businessId, String planValue, String paymentMethodId) {
        LOG.infof("Creating subscription for business: %s with plan: %s", businessId, planValue);

        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Business not found"));
                }

                Business business = businessOpt.get();

                // Update the business with the new plan
                SubscriptionPlan plan = SubscriptionPlan.fromValue(planValue);
                if (plan == null) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Invalid subscription plan"));
                }

                Business updatedBusiness = new Business(
                    business.getId(),
                    business.getName(),
                    plan.getValue(),
                    "active",
                    Instant.now(),
                    Instant.now().plus(30, ChronoUnit.DAYS),
                    paymentMethodId,
                    business.getCreatedAt(),
                    Instant.now(),
                    business.getSettings(),
                    business.getMetadata(),
                    business.getCurrentConnectorUsageBytes(),
                    business.getDocumentsProcessedThisMonth(),
                    business.getQueriesThisMonth()
                );

                return userRepository.saveBusiness(updatedBusiness)
                    .chain(() -> paymentProvider.createSubscription(updatedBusiness, paymentMethodId));
            });
    }

    /**
     * Update a subscription for a business.
     */
    public Uni<String> updateSubscription(String businessId, String subscriptionId, String planValue, String paymentMethodId) {
        LOG.infof("Updating subscription for business: %s with plan: %s", businessId, planValue);

        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Business not found"));
                }

                Business business = businessOpt.get();

                // Update the business with the new plan
                SubscriptionPlan plan = SubscriptionPlan.fromValue(planValue);
                if (plan == null) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Invalid subscription plan"));
                }

                Business updatedBusiness = new Business(
                    business.getId(),
                    business.getName(),
                    plan.getValue(),
                    "active",
                    business.getSubscriptionStartDate(),
                    business.getNextBillingDate(),
                    paymentMethodId != null ? paymentMethodId : business.getPaymentMethodId(),
                    business.getCreatedAt(),
                    Instant.now(),
                    business.getSettings(),
                    business.getMetadata(),
                    business.getCurrentConnectorUsageBytes(),
                    business.getDocumentsProcessedThisMonth(),
                    business.getQueriesThisMonth()
                );

                return userRepository.saveBusiness(updatedBusiness)
                    .chain(() -> paymentProvider.updateSubscription(updatedBusiness, subscriptionId));
            });
    }

    /**
     * Cancel a subscription for a business.
     */
    public Uni<String> cancelSubscription(String businessId, String subscriptionId, boolean immediate) {
        LOG.infof("Canceling subscription for business: %s, immediate: %s", businessId, immediate);

        return userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    return Uni.createFrom().failure(new IllegalArgumentException("Business not found"));
                }

                Business business = businessOpt.get();

                // Update the business subscription status
                Business updatedBusiness = new Business(
                    business.getId(),
                    business.getName(),
                    business.getSubscriptionPlan(),
                    immediate ? "canceled" : "canceling",
                    business.getSubscriptionStartDate(),
                    business.getNextBillingDate(),
                    business.getPaymentMethodId(),
                    business.getCreatedAt(),
                    Instant.now(),
                    business.getSettings(),
                    business.getMetadata(),
                    business.getCurrentConnectorUsageBytes(),
                    business.getDocumentsProcessedThisMonth(),
                    business.getQueriesThisMonth()
                );

                return userRepository.saveBusiness(updatedBusiness)
                    .chain(() -> paymentProvider.cancelSubscription(subscriptionId, immediate));
            });
    }

    /**
     * Get transactions for a business.
     */
    public Uni<List<BillingTransaction>> getBusinessTransactions(String businessId, Instant fromDate, Instant toDate) {
        LOG.infof("Getting transactions for business: %s", businessId);
        return billingRepository.getBusinessTransactions(businessId, fromDate, toDate);
    }

    /**
     * Get transactions of a specific type for a business.
     */
    public Uni<List<BillingTransaction>> getBusinessTransactionsByType(
            String businessId, BillingTransaction.TransactionType type, Instant fromDate, Instant toDate) {
        LOG.infof("Getting transactions of type %s for business: %s", type, businessId);
        return billingRepository.getBusinessTransactionsByType(businessId, type, fromDate, toDate);
    }
}
