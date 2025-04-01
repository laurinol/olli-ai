/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.models.BillingTransaction;
import pro.olli.models.Business;
import pro.olli.repository.BillingRepository;
import pro.olli.repository.Factory;
import pro.olli.repository.UserRepository;
import pro.olli.service.payment.PaymentProvider;
import pro.olli.service.payment.PaymentProviderFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduler for billing jobs like monthly recurring payments and usage metric resets.
 */
@ApplicationScoped
public class BillingJobScheduler {

    private static final Logger LOG = Logger.getLogger(BillingJobScheduler.class);

    private final UserRepository userRepository;
    private final BillingRepository billingRepository;
    private final PaymentProviderFactory paymentProviderFactory;
    private final boolean billingEnabled;

    @Inject
    public BillingJobScheduler(
            UserRepository userRepository,
            BillingRepository billingRepository,
            PaymentProviderFactory paymentProviderFactory,
            @ConfigProperty(name = "billing.enabled", defaultValue = "false") boolean billingEnabled) {
        this.userRepository = userRepository;
        this.billingRepository = billingRepository;
        this.paymentProviderFactory = paymentProviderFactory;
        this.billingEnabled = billingEnabled;

        LOG.infof("BillingJobScheduler initialized with billing enabled: %s", billingEnabled);
    }

    /**
     * Scheduler job that runs daily to process any recurring payments due today
     * and reset usage metrics for businesses that have been billed.
     */
    @Scheduled(cron = "0 0 3 * * ?") // Run at 3 AM every day
    void dailyBillingJob() {
        if (!billingEnabled) {
            LOG.info("Billing is disabled, skipping daily billing job");
            return;
        }

        LOG.info("Running daily billing job to process due payments");
        processDuePayments();
    }

    /**
     * Process payments for businesses whose billing date is today or past due.
     */
    private void processDuePayments() {
        // Get today's date at midnight
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);

        // Query businesses with next billing date on or before today
        userRepository.getBusinessesDueForBilling(today)
            .subscribe().with(
                businesses -> {
                    LOG.infof("Processing payments for %d businesses due for billing", businesses.size());

                    for (Business business : businesses) {
                        try {
                            processBusiness(business);
                        } catch (Exception e) {
                            LOG.errorf("Error processing business %s for billing: %s",
                                   business.getId(), e.getMessage());
                        }
                    }
                },
                e -> LOG.errorf("Failed to get businesses due for billing: %s", e.getMessage())
            );
    }

    /**
     * Process a single business for billing.
     */
    private void processBusiness(Business business) {
        LOG.infof("Processing billing for business: %s (%s)", business.getName(), business.getId());

        // Skip if subscription is not active
        if (!business.isSubscriptionActive()) {
            LOG.infof("Skipping business %s as subscription is not active: %s",
                    business.getId(), business.getSubscriptionStatus());
            return;
        }

        // Skip if no payment method is set
        if (business.getPaymentMethodId() == null) {
            LOG.warnf("Business %s has no payment method set", business.getId());
            return;
        }

        // Get the payment provider
        PaymentProvider paymentProvider = paymentProviderFactory.getPaymentProvider();

        // Get subscription ID from metadata
        String subscriptionId = getSubscriptionId(business);

        // Process the payment
        if (subscriptionId != null) {
            paymentProvider.processRecurringPayment(business, subscriptionId)
                .subscribe().with(
                    transaction -> {
                        LOG.infof("Successfully processed payment for business %s: $%.2f",
                                business.getId(), transaction.getAmountInDollars());

                        // Reset usage metrics for the business
                        resetUsageMetrics(business);
                    },
                    e -> LOG.errorf("Failed to process payment for business %s: %s",
                            business.getId(), e.getMessage())
                );
        } else {
            LOG.warnf("Business %s has no subscription ID in metadata", business.getId());
        }
    }

    /**
     * Reset usage metrics for a business after successful billing.
     */
    private void resetUsageMetrics(Business business) {
        LOG.infof("Resetting usage metrics for business: %s", business.getId());

        // Calculate next billing date (1 month from now)
        Instant nextBillingDate = Instant.now().plus(30, ChronoUnit.DAYS);

        // Create updated business with reset metrics
        Business updatedBusiness = new Business(
                business.getId(),
                business.getName(),
                business.getSubscriptionPlan(),
                business.getSubscriptionStatus(),
                business.getSubscriptionStartDate(),
                nextBillingDate,
                business.getPaymentMethodId(),
                business.getCreatedAt(),
                Instant.now(),
                business.getSettings(),
                business.getMetadata(),
                business.getCurrentConnectorUsageBytes(), // Keep connector usage
                0, // Reset documents processed
                0  // Reset queries
        );

        // Save updated business
        userRepository.saveBusiness(updatedBusiness)
            .subscribe().with(
                v -> LOG.infof("Reset usage metrics for business: %s, next billing date: %s",
                        business.getId(), nextBillingDate),
                e -> LOG.errorf("Failed to reset usage metrics for business %s: %s",
                        business.getId(), e.getMessage())
            );
    }

    /**
     * Get the subscription ID from business metadata.
     */
    private String getSubscriptionId(Business business) {
        if (business.getMetadata() == null) {
            return null;
        }

        Object subscriptionId = business.getMetadata().get("stripeSubscriptionId");
        return subscriptionId != null ? subscriptionId.toString() : null;
    }

    /**
     * Process failed payments that need retry.
     * This job runs after the main billing job to retry payments that failed.
     */
    @Scheduled(cron = "0 0 5 * * ?") // Run at 5 AM every day
    void retryFailedPaymentsJob() {
        if (!billingEnabled) {
            LOG.info("Billing is disabled, skipping retry failed payments job");
            return;
        }

        LOG.info("Running job to retry failed payments");

        // Get recent failed transactions
        Instant cutoff = Instant.now().minus(3, ChronoUnit.DAYS);

        billingRepository.getFailedPayments(cutoff)
            .subscribe().with(
                transactions -> {
                    LOG.infof("Processing %d failed payments for retry", transactions.size());

                    for (BillingTransaction transaction : transactions) {
                        try {
                            retryFailedPayment(transaction);
                        } catch (Exception e) {
                            LOG.errorf("Error retrying payment %s: %s",
                                   transaction.getId(), e.getMessage());
                        }
                    }
                },
                e -> LOG.errorf("Failed to get failed payments: %s", e.getMessage())
            );
    }

    /**
     * Retry a failed payment transaction.
     */
    private void retryFailedPayment(BillingTransaction transaction) {
        LOG.infof("Retrying failed payment: %s for business: %s ($%.2f)",
                transaction.getId(), transaction.getBusinessId(), transaction.getAmountInDollars());

        // Get the business
        userRepository.getBusiness(transaction.getBusinessId())
            .subscribe().with(
                businessOpt -> {
                    if (businessOpt.isEmpty()) {
                        LOG.warnf("Business %s not found for retry payment", transaction.getBusinessId());
                        return;
                    }

                    Business business = businessOpt.get();

                    // Skip if no payment method
                    if (business.getPaymentMethodId() == null) {
                        LOG.warnf("Business %s has no payment method for retry payment", business.getId());
                        return;
                    }

                    // Get the payment provider
                    PaymentProvider paymentProvider = paymentProviderFactory.getPaymentProvider();

                    // Create retry metadata
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("isRetry", true);
                    metadata.put("originalTransactionId", transaction.getId());

                    // Process the payment
                    paymentProvider.processPayment(
                            business,
                            business.getPaymentMethodId(),
                            transaction.getAmountInCents(),
                            transaction.getDescription() + " (Retry)",
                            metadata
                    ).subscribe().with(
                        newTransaction -> {
                            LOG.infof("Successfully retried payment for business %s: $%.2f",
                                    business.getId(), newTransaction.getAmountInDollars());

                            // Update the original transaction to reference the successful retry
                            updateRetryReference(transaction, newTransaction);
                        },
                        e -> LOG.errorf("Failed to retry payment for business %s: %s",
                                business.getId(), e.getMessage())
                    );
                },
                e -> LOG.errorf("Failed to get business for retry payment: %s", e.getMessage())
            );
    }

    /**
     * Update the original failed transaction to reference the successful retry.
     */
    private void updateRetryReference(BillingTransaction original, BillingTransaction retry) {
        // Add metadata to the original transaction referencing the successful retry
        Map<String, Object> updatedMetadata = original.getMetadata() != null
                ? new HashMap<>(original.getMetadata())
                : new HashMap<>();
        updatedMetadata.put("retriedWith", retry.getId());
        updatedMetadata.put("retrySuccessful", true);

        // Create updated transaction
        BillingTransaction updatedTransaction = new BillingTransaction(
                original.getId(),
                original.getBusinessId(),
                original.getType(),
                original.getStatus(), // Keep the failed status
                original.getAmountInCents(),
                original.getDescription(),
                original.getPaymentMethodId(),
                original.getExternalPaymentId(),
                original.getCreatedAt(),
                updatedMetadata
        );

        // Save updated transaction
        billingRepository.updateTransaction(updatedTransaction)
            .subscribe().with(
                v -> LOG.infof("Updated failed transaction %s with successful retry reference",
                        original.getId()),
                e -> LOG.errorf("Failed to update transaction with retry reference: %s", e.getMessage())
            );
    }
}
