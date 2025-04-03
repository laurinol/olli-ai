/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service.payment.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.BillingTransaction;
import pro.olli.models.Business;
import pro.olli.repository.BillingRepository;
import pro.olli.repository.Factory;
import pro.olli.repository.UserRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handles webhook events from Stripe to update subscription and payment status.
 */
@ApplicationScoped
public class StripeWebhookHandler {

    private static final Logger LOG = Logger.getLogger(StripeWebhookHandler.class);

    private final BillingRepository billingRepository;
    private final UserRepository userRepository;
    private final String webhookSecret;

    @Inject
    public StripeWebhookHandler(
            BillingRepository billingRepository,
            UserRepository userRepository,
            @ConfigProperty(name = "stripe.webhook.secret", defaultValue = "") String webhookSecret) {
        this.billingRepository = billingRepository;
        this.userRepository = userRepository;
        this.webhookSecret = webhookSecret;

        LOG.infof("StripeWebhookHandler initialized with webhook secret: %s...",
                webhookSecret.isEmpty() ? "EMPTY" : webhookSecret.substring(0, Math.min(4, webhookSecret.length())) + "****");
    }

    /**
     * Handle a webhook event from Stripe.
     *
     * @param payload The JSON payload from Stripe
     * @param sigHeader The Stripe-Signature header for verification
     * @return A Uni that completes when the event has been processed
     */

    /**
     * Verify and parse a Stripe webhook event.
     * This method is protected to allow overriding in tests.
     *
     * @param payload The JSON payload from Stripe
     * @param sigHeader The Stripe-Signature header for verification
     * @return The parsed Event object
     * @throws SignatureVerificationException if the signature is invalid
     * @throws Exception if there is an error parsing the event
     */
    protected Event verifyAndParseEvent(String payload, String sigHeader) throws SignatureVerificationException, Exception {
        return Webhook.constructEvent(payload, sigHeader, webhookSecret);
    }

    @Timed("stripe_webhook_processing")
    public Uni<Void> handleWebhookEvent(String payload, String sigHeader) {
        LOG.infof("Received Stripe webhook event with signature: %s", sigHeader);

        try {
            // Verify the webhook signature using the protected method
            Event event = verifyAndParseEvent(payload, sigHeader);
            LOG.infof("Verified Stripe webhook event: %s (id: %s)", event.getType(), event.getId());

            // Process the event based on its type
            return processEvent(event)
                .onFailure().invoke(e -> {
                    // Log the error but don't return a failure to Stripe
                    // This prevents Stripe from retrying events that we've already processed
                    LOG.errorf("Error processing Stripe webhook event %s (%s): %s",
                            event.getId(), event.getType(), e.getMessage());
                })
                .onFailure().recoverWithItem(e -> {
                    // Always acknowledge the webhook to Stripe, even on error
                    // We'll handle retries ourselves if needed
                    LOG.warnf("Acknowledging webhook event %s despite processing error", event.getId());
                    return null;
                });
        } catch (SignatureVerificationException e) {
            LOG.errorf("Invalid Stripe webhook signature: %s", e.getMessage());
            return Uni.createFrom().failure(
                    new AppException("Invalid Stripe webhook signature",
                            AppException.ErrorType.AUTHENTICATION_ERROR));
        } catch (Exception e) {
            LOG.errorf("Error processing Stripe webhook: %s", e.getMessage());
            return Uni.createFrom().failure(
                    new AppException("Error processing Stripe webhook", e,
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
        }
    }

    /**
     * Process a Stripe webhook event based on its type.
     *
     * @param event The Stripe event to process
     * @return A Uni that completes when the event has been processed
     */
    private Uni<Void> processEvent(Event event) {
        switch (event.getType()) {
            case "payment_intent.succeeded":
                return handlePaymentIntentSucceeded(event);

            case "payment_intent.payment_failed":
                return handlePaymentIntentFailed(event);

            case "invoice.payment_succeeded":
                return handleInvoicePaymentSucceeded(event);

            case "invoice.payment_failed":
                return handleInvoicePaymentFailed(event);

            case "customer.subscription.updated":
                return handleSubscriptionUpdated(event);

            case "customer.subscription.deleted":
                return handleSubscriptionDeleted(event);

            default:
                LOG.infof("Ignoring unhandled Stripe event type: %s", event.getType());
                return Uni.createFrom().voidItem();
        }
    }

    /**
     * Handle a payment_intent.succeeded event.
     *
     * @param event The Stripe event
     * @return A Uni that completes when the event has been processed
     */
    private Uni<Void> handlePaymentIntentSucceeded(Event event) {
        LOG.infof("Processing payment_intent.succeeded event: %s", event.getId());

        // Get the PaymentIntent object from the event
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (!dataObjectDeserializer.getObject().isPresent()) {
            LOG.warnf("Could not deserialize event data object: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        StripeObject stripeObject = dataObjectDeserializer.getObject().get();
        if (!(stripeObject instanceof PaymentIntent)) {
            LOG.warnf("Event object not a PaymentIntent: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        String paymentIntentId = paymentIntent.getId();

        // Find the transaction related to this payment intent
        return billingRepository.getTransactionByExternalId(paymentIntentId)
                .onItem().transformToUni(optionalTransaction -> {
                    if (optionalTransaction.isEmpty()) {
                        LOG.warnf("No transaction found for payment intent: %s", paymentIntentId);
                        return Uni.createFrom().voidItem();
                    }

                    BillingTransaction transaction = optionalTransaction.get();

                    // Update the transaction status to COMPLETED
                    BillingTransaction updatedTransaction = transaction
                            .withStatus(BillingTransaction.TransactionStatus.COMPLETED);

                    LOG.infof("Updating transaction %s to COMPLETED for payment intent: %s",
                            transaction.getId(), paymentIntentId);

                    return billingRepository.updateTransaction(updatedTransaction)
                            .onItem().ignore().andContinueWithNull()
                            .onFailure().recoverWithItem(e -> {
                                LOG.errorf("Failed to update transaction status: %s", e.getMessage());
                                return null;
                            });
                });
    }

    /**
     * Handle a payment_intent.payment_failed event.
     *
     * @param event The Stripe event
     * @return A Uni that completes when the event has been processed
     */
    private Uni<Void> handlePaymentIntentFailed(Event event) {
        LOG.infof("Processing payment_intent.payment_failed event: %s", event.getId());

        // Get the PaymentIntent object from the event
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (!dataObjectDeserializer.getObject().isPresent()) {
            LOG.warnf("Could not deserialize event data object: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        StripeObject stripeObject = dataObjectDeserializer.getObject().get();
        if (!(stripeObject instanceof PaymentIntent)) {
            LOG.warnf("Event object not a PaymentIntent: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        String paymentIntentId = paymentIntent.getId();

        // Find the transaction related to this payment intent
        return billingRepository.getTransactionByExternalId(paymentIntentId)
                .onItem().transformToUni(optionalTransaction -> {
                    if (optionalTransaction.isEmpty()) {
                        LOG.warnf("No transaction found for payment intent: %s", paymentIntentId);
                        return Uni.createFrom().voidItem();
                    }

                    BillingTransaction transaction = optionalTransaction.get();

                    // Update the transaction status to FAILED
                    BillingTransaction updatedTransaction = transaction
                            .withStatus(BillingTransaction.TransactionStatus.FAILED);

                    LOG.infof("Updating transaction %s to FAILED for payment intent: %s",
                            transaction.getId(), paymentIntentId);

                    return billingRepository.updateTransaction(updatedTransaction)
                            .onItem().ignore().andContinueWithNull()
                            .onFailure().recoverWithItem(e -> {
                                LOG.errorf("Failed to update transaction status: %s", e.getMessage());
                                return null;
                            });
                });
    }

    /**
     * Handle an invoice.payment_succeeded event.
     *
     * @param event The Stripe event
     * @return A Uni that completes when the event has been processed
     */
    private Uni<Void> handleInvoicePaymentSucceeded(Event event) {
        LOG.infof("Processing invoice.payment_succeeded event: %s", event.getId());

        // Get the Invoice object from the event
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (!dataObjectDeserializer.getObject().isPresent()) {
            LOG.warnf("Could not deserialize event data object: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        StripeObject stripeObject = dataObjectDeserializer.getObject().get();
        if (!(stripeObject instanceof Invoice)) {
            LOG.warnf("Event object not an Invoice: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        Invoice invoice = (Invoice) stripeObject;
        String subscriptionId = invoice.getSubscription();

        if (subscriptionId == null) {
            LOG.warnf("Invoice has no subscription ID: %s", invoice.getId());
            return Uni.createFrom().voidItem();
        }

        // First, find the business with this subscription ID
        return findBusinessBySubscriptionId(subscriptionId)
                .onItem().transformToUni(optionalBusiness -> {
                    if (optionalBusiness.isEmpty()) {
                        LOG.warnf("No business found for subscription: %s", subscriptionId);
                        return Uni.createFrom().voidItem();
                    }

                    Business business = optionalBusiness.get();

                    // Create a transaction record for this successful payment if it doesn't exist
                    return billingRepository.getTransactionByExternalId(invoice.getPaymentIntent())
                            .onItem().transformToUni(optionalTransaction -> {
                                if (optionalTransaction.isPresent()) {
                                    // Transaction already exists, update it to COMPLETED
                                    BillingTransaction transaction = optionalTransaction.get();
                                    BillingTransaction updatedTransaction = transaction
                                            .withStatus(BillingTransaction.TransactionStatus.COMPLETED);

                                    LOG.infof("Updating existing transaction %s to COMPLETED for invoice: %s",
                                            transaction.getId(), invoice.getId());

                                    return billingRepository.updateTransaction(updatedTransaction)
                                            .onItem().ignore().andContinueWithNull();
                                } else {
                                    // Create a new transaction record
                                    int amountInCents = invoice.getAmountPaid().intValue();
                                    String description = String.format("Subscription payment - Invoice %s", invoice.getId());

                                    Map<String, Object> metadata = new HashMap<>();
                                    metadata.put("invoiceId", invoice.getId());
                                    metadata.put("subscriptionId", subscriptionId);
                                    metadata.put("isRecurring", true);

                                    BillingTransaction transaction = new BillingTransaction(
                                            business.getId(),
                                            BillingTransaction.TransactionType.SUBSCRIPTION_PAYMENT,
                                            BillingTransaction.TransactionStatus.COMPLETED,
                                            amountInCents,
                                            description,
                                            business.getPaymentMethodId(),
                                            invoice.getPaymentIntent(),
                                            metadata
                                    );

                                    LOG.infof("Creating new COMPLETED transaction for invoice: %s", invoice.getId());

                                    return billingRepository.createTransaction(transaction)
                                            .onItem().ignore().andContinueWithNull();
                                }
                            });
                });
    }

    /**
     * Handle an invoice.payment_failed event.
     *
     * @param event The Stripe event
     * @return A Uni that completes when the event has been processed
     */
    private Uni<Void> handleInvoicePaymentFailed(Event event) {
        LOG.infof("Processing invoice.payment_failed event: %s", event.getId());

        // Get the Invoice object from the event
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (!dataObjectDeserializer.getObject().isPresent()) {
            LOG.warnf("Could not deserialize event data object: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        StripeObject stripeObject = dataObjectDeserializer.getObject().get();
        if (!(stripeObject instanceof Invoice)) {
            LOG.warnf("Event object not an Invoice: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        Invoice invoice = (Invoice) stripeObject;
        String subscriptionId = invoice.getSubscription();

        if (subscriptionId == null) {
            LOG.warnf("Invoice has no subscription ID: %s", invoice.getId());
            return Uni.createFrom().voidItem();
        }

        // First, find the business with this subscription ID
        return findBusinessBySubscriptionId(subscriptionId)
                .onItem().transformToUni(optionalBusiness -> {
                    if (optionalBusiness.isEmpty()) {
                        LOG.warnf("No business found for subscription: %s", subscriptionId);
                        return Uni.createFrom().voidItem();
                    }

                    Business business = optionalBusiness.get();

                    // Create a transaction record for this failed payment if it doesn't exist
                    return billingRepository.getTransactionByExternalId(invoice.getPaymentIntent())
                            .onItem().transformToUni(optionalTransaction -> {
                                if (optionalTransaction.isPresent()) {
                                    // Transaction already exists, update it to FAILED
                                    BillingTransaction transaction = optionalTransaction.get();
                                    BillingTransaction updatedTransaction = transaction
                                            .withStatus(BillingTransaction.TransactionStatus.FAILED);

                                    LOG.infof("Updating existing transaction %s to FAILED for invoice: %s",
                                            transaction.getId(), invoice.getId());

                                    return billingRepository.updateTransaction(updatedTransaction)
                                            .onItem().ignore().andContinueWithNull();
                                } else {
                                    // Create a new transaction record
                                    int amountInCents = invoice.getAmountDue().intValue();
                                    String description = String.format("Failed subscription payment - Invoice %s", invoice.getId());

                                    Map<String, Object> metadata = new HashMap<>();
                                    metadata.put("invoiceId", invoice.getId());
                                    metadata.put("subscriptionId", subscriptionId);
                                    metadata.put("isRecurring", true);

                                    BillingTransaction transaction = new BillingTransaction(
                                            business.getId(),
                                            BillingTransaction.TransactionType.SUBSCRIPTION_PAYMENT,
                                            BillingTransaction.TransactionStatus.FAILED,
                                            amountInCents,
                                            description,
                                            business.getPaymentMethodId(),
                                            invoice.getPaymentIntent(),
                                            metadata
                                    );

                                    LOG.infof("Creating new FAILED transaction for invoice: %s", invoice.getId());

                                    return billingRepository.createTransaction(transaction)
                                            .onItem().ignore().andContinueWithNull();
                                }
                            });
                });
    }

    /**
     * Handle a customer.subscription.updated event.
     *
     * @param event The Stripe event
     * @return A Uni that completes when the event has been processed
     */
    private Uni<Void> handleSubscriptionUpdated(Event event) {
        LOG.infof("Processing customer.subscription.updated event: %s", event.getId());

        // Get the Subscription object from the event
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (!dataObjectDeserializer.getObject().isPresent()) {
            LOG.warnf("Could not deserialize event data object: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        StripeObject stripeObject = dataObjectDeserializer.getObject().get();
        if (!(stripeObject instanceof Subscription)) {
            LOG.warnf("Event object not a Subscription: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        Subscription subscription = (Subscription) stripeObject;
        String subscriptionId = subscription.getId();

        // Find the business with this subscription ID
        return findBusinessBySubscriptionId(subscriptionId)
                .onItem().transformToUni(optionalBusiness -> {
                    if (optionalBusiness.isEmpty()) {
                        LOG.warnf("No business found for subscription: %s", subscriptionId);
                        return Uni.createFrom().voidItem();
                    }

                    Business business = optionalBusiness.get();

                    // Update the business's next billing date and subscription status
                    String status = "active";
                    if (subscription.getCancelAtPeriodEnd()) {
                        status = "canceling"; // Will be canceled at the end of the period
                    } else if ("canceled".equals(subscription.getStatus())) {
                        status = "canceled";
                    }

                    Business updatedBusiness = new Business(
                            business.getId(),
                            business.getName(),
                            business.getSubscriptionPlan(),
                            status,
                            business.getSubscriptionStartDate(),
                            Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()),
                            business.getPaymentMethodId(),
                            business.getCreatedAt(),
                            Instant.now(),
                            business.getSettings(),
                            business.getMetadata(),
                            business.getCurrentConnectorUsageBytes(),
                            business.getDocumentsProcessedThisMonth(),
                            business.getQueriesThisMonth()
                    );

                    LOG.infof("Updating business %s with subscription status: %s, next billing date: %s",
                            business.getId(), status, Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()));

                    return userRepository.saveBusiness(updatedBusiness).onItem().ignore().andContinueWithNull();
                });
    }

    /**
     * Handle a customer.subscription.deleted event.
     *
     * @param event The Stripe event
     * @return A Uni that completes when the event has been processed
     */
    private Uni<Void> handleSubscriptionDeleted(Event event) {
        LOG.infof("Processing customer.subscription.deleted event: %s", event.getId());

        // Get the Subscription object from the event
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (!dataObjectDeserializer.getObject().isPresent()) {
            LOG.warnf("Could not deserialize event data object: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        StripeObject stripeObject = dataObjectDeserializer.getObject().get();
        if (!(stripeObject instanceof Subscription)) {
            LOG.warnf("Event object not a Subscription: %s", event.getId());
            return Uni.createFrom().voidItem();
        }

        Subscription subscription = (Subscription) stripeObject;
        String subscriptionId = subscription.getId();

        // Find the business with this subscription ID
        return findBusinessBySubscriptionId(subscriptionId)
                .onItem().transformToUni(optionalBusiness -> {
                    if (optionalBusiness.isEmpty()) {
                        LOG.warnf("No business found for subscription: %s", subscriptionId);
                        return Uni.createFrom().voidItem();
                    }

                    Business business = optionalBusiness.get();

                    // Update the business's subscription status to canceled and downgrade to free tier
                    Business updatedBusiness = new Business(
                            business.getId(),
                            business.getName(),
                            "FREE", // Downgrade to free tier
                            "canceled",
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

                    LOG.infof("Updating business %s to FREE tier with canceled status due to subscription deletion",
                            business.getId());

                    return userRepository.saveBusiness(updatedBusiness).onItem().ignore().andContinueWithNull();
                });
    }

    /**
     * Find a business by its Stripe subscription ID.
     *
     * @param subscriptionId The Stripe subscription ID
     * @return A Uni that emits an Optional<Business> when complete
     */
    private Uni<Optional<Business>> findBusinessBySubscriptionId(String subscriptionId) {
        LOG.infof("Looking up business by subscription ID: %s", subscriptionId);

        // Use the dedicated repository method for efficient lookup
        return userRepository.getBusinessBySubscriptionId(subscriptionId)
                .onItem().invoke(optionalBusiness -> {
                    if (optionalBusiness.isPresent()) {
                        LOG.infof("Found business %s for subscription ID: %s",
                                optionalBusiness.get().getId(), subscriptionId);
                    } else {
                        LOG.warnf("No business found for subscription ID: %s", subscriptionId);
                    }
                });
    }
}
