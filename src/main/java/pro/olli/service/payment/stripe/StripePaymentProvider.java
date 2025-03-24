/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service.payment.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PaymentMethodCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.models.BillingTransaction;
import pro.olli.models.Business;
import pro.olli.models.SubscriptionPlan;
import pro.olli.repository.BillingRepository;
import pro.olli.repository.Factory;
import pro.olli.repository.UserRepository;
import pro.olli.service.payment.PaymentProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stripe implementation of the PaymentProvider interface.
 * Handles recurring credit card charges and subscription management.
 */
@ApplicationScoped
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger LOG = Logger.getLogger(StripePaymentProvider.class);

    private final Vertx vertx;
    private final BillingRepository billingRepository;
    private final UserRepository userRepository;
    private final Map<SubscriptionPlan, String> planPriceIds;

    @Inject
    public StripePaymentProvider(
            Vertx vertx,
            @Factory BillingRepository billingRepository,
            @Factory UserRepository userRepository,
            @ConfigProperty(name = "stripe.api.key") String stripeApiKey,
            @ConfigProperty(name = "stripe.price.basic", defaultValue = "price_basic") String basicPriceId,
            @ConfigProperty(name = "stripe.price.professional", defaultValue = "price_professional") String professionalPriceId,
            @ConfigProperty(name = "stripe.price.enterprise", defaultValue = "price_enterprise") String enterprisePriceId) {
        
        this.vertx = vertx;
        this.billingRepository = billingRepository;
        this.userRepository = userRepository;
        
        // Initialize Stripe API key
        Stripe.apiKey = stripeApiKey;
        
        // Map subscription plans to Stripe price IDs
        this.planPriceIds = new HashMap<>();
        this.planPriceIds.put(SubscriptionPlan.BASIC, basicPriceId);
        this.planPriceIds.put(SubscriptionPlan.PROFESSIONAL, professionalPriceId);
        this.planPriceIds.put(SubscriptionPlan.ENTERPRISE, enterprisePriceId);
        
        LOG.infof("StripePaymentProvider initialized with API key: %s...", 
                stripeApiKey.substring(0, Math.min(8, stripeApiKey.length())) + "****");
    }

    @Override
    public Uni<String> createPaymentMethod(String businessId, Map<String, Object> paymentDetails) {
        LOG.infof("Creating payment method for business: %s", businessId);
        
        return Uni.createFrom().emitter(emitter -> {
            vertx.executeBlocking(() -> {
                try {
                    // First get or create a Stripe customer for this business
                    Customer customer = getOrCreateCustomer(businessId);
                    
                    // Create a payment method with the provided card details
                    PaymentMethodCreateParams params = PaymentMethodCreateParams.builder()
                        .setType(PaymentMethodCreateParams.Type.CARD)
                        .setCard(
                            PaymentMethodCreateParams.CardDetails.builder()
                                .setNumber((String) paymentDetails.get("cardNumber"))
                                .setExpMonth(Long.parseLong((String) paymentDetails.get("expMonth")))
                                .setExpYear(Long.parseLong((String) paymentDetails.get("expYear")))
                                .setCvc((String) paymentDetails.get("cvc"))
                                .build()
                        )
                        .build();
                    
                    PaymentMethod paymentMethod = PaymentMethod.create(params);
                    LOG.infof("Created payment method: %s", paymentMethod.getId());
                    
                    // Attach the payment method to the customer
                    PaymentMethodAttachParams attachParams = PaymentMethodAttachParams.builder()
                        .setCustomer(customer.getId())
                        .build();
                    
                    paymentMethod.attach(attachParams);
                    LOG.infof("Attached payment method %s to customer %s", 
                            paymentMethod.getId(), customer.getId());
                    
                    // Store the customer ID in the business metadata if not already there
                    storeCustomerId(businessId, customer.getId());
                    
                    return paymentMethod.getId();
                } catch (StripeException e) {
                    LOG.errorf("Failed to create payment method: %s", e.getMessage());
                    throw new AppException("Failed to create payment method: " + e.getMessage(), 
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .subscribe().with(
                emitter::complete,
                emitter::fail
            );
        });
    }

    @Override
    public Uni<String> updatePaymentMethod(String paymentMethodId, Map<String, Object> paymentDetails) {
        LOG.infof("Updating payment method: %s", paymentMethodId);
        
        return Uni.createFrom().emitter(emitter -> {
            vertx.executeBlocking(() -> {
                try {
                    // Retrieve the payment method
                    PaymentMethod paymentMethod = PaymentMethod.retrieve(paymentMethodId);
                    
                    // Build the update parameters
                    Map<String, Object> updateParams = new HashMap<>();
                    Map<String, Object> cardParams = new HashMap<>();
                    
                    if (paymentDetails.containsKey("expMonth")) {
                        cardParams.put("exp_month", Long.parseLong((String) paymentDetails.get("expMonth")));
                    }
                    
                    if (paymentDetails.containsKey("expYear")) {
                        cardParams.put("exp_year", Long.parseLong((String) paymentDetails.get("expYear")));
                    }
                    
                    if (!cardParams.isEmpty()) {
                        updateParams.put("card", cardParams);
                    }
                    
                    // Update the payment method if there are parameters to update
                    if (!updateParams.isEmpty()) {
                        paymentMethod = paymentMethod.update(updateParams);
                        LOG.infof("Updated payment method: %s", paymentMethod.getId());
                    }
                    
                    return paymentMethod.getId();
                } catch (StripeException e) {
                    LOG.errorf("Failed to update payment method: %s", e.getMessage());
                    throw new AppException("Failed to update payment method: " + e.getMessage(), 
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .subscribe().with(
                emitter::complete,
                emitter::fail
            );
        });
    }

    @Override
    public Uni<Map<String, Object>> getPaymentMethod(String paymentMethodId) {
        LOG.infof("Retrieving payment method: %s", paymentMethodId);
        
        return Uni.createFrom().emitter(emitter -> {
            vertx.executeBlocking(() -> {
                try {
                    // Retrieve the payment method
                    PaymentMethod paymentMethod = PaymentMethod.retrieve(paymentMethodId);
                    
                    // Extract the payment method details
                    Map<String, Object> details = new HashMap<>();
                    
                    if (paymentMethod.getType().equals("card") && paymentMethod.getCard() != null) {
                        PaymentMethod.Card card = paymentMethod.getCard();
                        details.put("type", "card");
                        details.put("brand", card.getBrand());
                        details.put("last4", card.getLast4());
                        details.put("expMonth", card.getExpMonth());
                        details.put("expYear", card.getExpYear());
                        details.put("country", card.getCountry());
                    }
                    
                    details.put("id", paymentMethod.getId());
                    details.put("created", Instant.ofEpochSecond(paymentMethod.getCreated()));
                    
                    return details;
                } catch (StripeException e) {
                    LOG.errorf("Failed to retrieve payment method: %s", e.getMessage());
                    throw new AppException("Failed to retrieve payment method: " + e.getMessage(), 
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .subscribe().with(
                emitter::complete,
                emitter::fail
            );
        });
    }

    @Override
    public Uni<String> createSubscription(Business business, String paymentMethodId) {
        LOG.infof("Creating subscription for business: %s with payment method: %s", 
                business.getId(), paymentMethodId);
        
        // Get the Stripe price ID for the business's subscription plan
        SubscriptionPlan plan = business.getSubscriptionPlanEnum();
        String priceId = planPriceIds.getOrDefault(plan, planPriceIds.get(SubscriptionPlan.BASIC));
        
        if (priceId == null) {
            LOG.errorf("No price ID configured for plan: %s", plan.getValue());
            return Uni.createFrom().failure(
                    new AppException("No price ID configured for plan: " + plan.getValue(), 
                            AppException.ErrorType.VALIDATION_ERROR));
        }
        
        return Uni.createFrom().emitter(emitter -> {
            vertx.executeBlocking(() -> {
                try {
                    // Get or create a Stripe customer for this business
                    Customer customer = getOrCreateCustomer(business.getId());
                    
                    // Create the subscription
                    SubscriptionCreateParams params = SubscriptionCreateParams.builder()
                        .setCustomer(customer.getId())
                        .addItem(
                            SubscriptionCreateParams.Item.builder()
                                .setPrice(priceId)
                                .build()
                        )
                        .setDefaultPaymentMethod(paymentMethodId)
                        .build();
                    
                    Subscription subscription = Subscription.create(params);
                    LOG.infof("Created subscription: %s for business: %s", 
                            subscription.getId(), business.getId());
                    
                    // Store subscription ID in the business metadata
                    Map<String, Object> metadata = business.getMetadata() != null 
                            ? new HashMap<>(business.getMetadata()) 
                            : new HashMap<>();
                    metadata.put("stripeSubscriptionId", subscription.getId());
                    
                    // Update business with subscription metadata
                    Business updatedBusiness = new Business(
                            business.getId(),
                            business.getName(),
                            business.getSubscriptionPlan(),
                            "active", // Update status now that subscription is active
                            business.getSubscriptionStartDate(),
                            Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()), // Set next billing date from Stripe
                            paymentMethodId,
                            business.getCreatedAt(),
                            Instant.now(),
                            business.getSettings(),
                            metadata,
                            business.getCurrentConnectorUsageBytes(),
                            business.getDocumentsProcessedThisMonth(),
                            business.getQueriesThisMonth()
                    );
                    
                    // Store the subscription ID for use in lambda
                    final String subscriptionId = subscription.getId();
                    return userRepository.saveBusiness(updatedBusiness)
                            .map(v -> subscriptionId)
                            .await().indefinitely();
                } catch (StripeException e) {
                    LOG.errorf("Failed to create subscription: %s", e.getMessage());
                    throw new AppException("Failed to create subscription: " + e.getMessage(), 
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .subscribe().with(
                emitter::complete,
                emitter::fail
            );
        });
    }

    @Override
    public Uni<String> updateSubscription(Business business, String subscriptionId) {
        LOG.infof("Updating subscription: %s for business: %s", subscriptionId, business.getId());
        
        // Get the Stripe price ID for the business's subscription plan
        SubscriptionPlan plan = business.getSubscriptionPlanEnum();
        String priceId = planPriceIds.getOrDefault(plan, planPriceIds.get(SubscriptionPlan.BASIC));
        
        if (priceId == null) {
            LOG.errorf("No price ID configured for plan: %s", plan.getValue());
            return Uni.createFrom().failure(
                    new AppException("No price ID configured for plan: " + plan.getValue(), 
                            AppException.ErrorType.VALIDATION_ERROR));
        }
        
        return Uni.createFrom().emitter(emitter -> {
            vertx.executeBlocking(() -> {
                try {
                    // Retrieve the subscription
                    Subscription subscription = Subscription.retrieve(subscriptionId);
                    
                    // Update the subscription
                    SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                        .addItem(
                            SubscriptionUpdateParams.Item.builder()
                                .setId(subscription.getItems().getData().get(0).getId())
                                .setPrice(priceId)
                                .build()
                        )
                        .build();
                    
                    if (business.getPaymentMethodId() != null) {
                        // Create new params if payment method needs to be updated
                        params = SubscriptionUpdateParams.builder()
                                .addItem(
                                    SubscriptionUpdateParams.Item.builder()
                                        .setId(subscription.getItems().getData().get(0).getId())
                                        .setPrice(priceId)
                                        .build()
                                )
                                .setDefaultPaymentMethod(business.getPaymentMethodId())
                                .build();
                    }
                    
                    subscription = subscription.update(params);
                    LOG.infof("Updated subscription: %s for business: %s", 
                            subscription.getId(), business.getId());
                    
                    // Update business with next billing date
                    Business updatedBusiness = new Business(
                            business.getId(),
                            business.getName(),
                            business.getSubscriptionPlan(),
                            "active", // Make sure status is active
                            business.getSubscriptionStartDate(),
                            Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()), // Update next billing date
                            business.getPaymentMethodId(),
                            business.getCreatedAt(),
                            Instant.now(),
                            business.getSettings(),
                            business.getMetadata(),
                            business.getCurrentConnectorUsageBytes(),
                            business.getDocumentsProcessedThisMonth(),
                            business.getQueriesThisMonth()
                    );
                    
                    // Store the subscription ID for use in lambda
                    final String updatedSubId = subscription.getId();
                    return userRepository.saveBusiness(updatedBusiness)
                            .map(v -> updatedSubId)
                            .await().indefinitely();
                } catch (StripeException e) {
                    LOG.errorf("Failed to update subscription: %s", e.getMessage());
                    throw new AppException("Failed to update subscription: " + e.getMessage(), 
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .subscribe().with(
                emitter::complete,
                emitter::fail
            );
        });
    }

    @Override
    public Uni<String> cancelSubscription(String subscriptionId, boolean immediateEffect) {
        LOG.infof("Cancelling subscription: %s with immediate effect: %s", 
                subscriptionId, immediateEffect);
        
        return Uni.createFrom().emitter(emitter -> {
            vertx.executeBlocking(() -> {
                try {
                    // Retrieve the subscription
                    Subscription subscription = Subscription.retrieve(subscriptionId);
                    
                    // Cancel the subscription
                    Map<String, Object> cancelParams = new HashMap<>();
                    cancelParams.put("cancel_at_period_end", !immediateEffect);
                    
                    subscription = subscription.cancel(cancelParams);
                    
                    String status = immediateEffect ? "canceled" : "will cancel at period end";
                    LOG.infof("Subscription: %s %s", subscriptionId, status);
                    
                    return status;
                } catch (StripeException e) {
                    LOG.errorf("Failed to cancel subscription: %s", e.getMessage());
                    throw new AppException("Failed to cancel subscription: " + e.getMessage(), 
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .subscribe().with(
                emitter::complete,
                emitter::fail
            );
        });
    }

    @Override
    public Uni<BillingTransaction> processPayment(
            Business business,
            String paymentMethodId,
            int amountInCents,
            String description,
            Map<String, Object> metadata) {
        
        LOG.infof("Processing payment of $%.2f for business: %s with payment method: %s", 
                amountInCents / 100.0, business.getId(), paymentMethodId);
        
        // Create a pending transaction
        BillingTransaction pendingTransaction = new BillingTransaction(
                business.getId(),
                BillingTransaction.TransactionType.SUBSCRIPTION_PAYMENT,
                BillingTransaction.TransactionStatus.PENDING,
                amountInCents,
                description,
                paymentMethodId,
                null, // externalPaymentId will be set after processing
                metadata
        );
        
        return billingRepository.createTransaction(pendingTransaction)
                .flatMap(transaction -> 
                    Uni.createFrom().emitter(emitter -> {
                        vertx.executeBlocking(() -> {
                            try {
                                // Get or create a Stripe customer for this business
                                Customer customer = getOrCreateCustomer(business.getId());
                                
                                // Create the charge
                                ChargeCreateParams params = ChargeCreateParams.builder()
                                    .setAmount((long) amountInCents)
                                    .setCurrency("usd")
                                    .setCustomer(customer.getId())
                                    .setSource(paymentMethodId)
                                    .setDescription(description)
                                    .build();
                                
                                Charge charge = Charge.create(params);
                                LOG.infof("Created charge: %s for business: %s", 
                                        charge.getId(), business.getId());
                                
                                // Update transaction with charge ID and status
                                BillingTransaction completedTransaction = transaction
                                        .withExternalPaymentId(charge.getId())
                                        .withStatus(BillingTransaction.TransactionStatus.COMPLETED);
                                
                                return billingRepository.updateTransaction(completedTransaction)
                                        .await().indefinitely();
                            } catch (StripeException e) {
                                LOG.errorf("Failed to process payment: %s", e.getMessage());
                                
                                // Update transaction with failed status
                                BillingTransaction failedTransaction = transaction
                                        .withStatus(BillingTransaction.TransactionStatus.FAILED);
                                
                                billingRepository.updateTransaction(failedTransaction)
                                        .subscribe().with(
                                            v -> {}, 
                                            ex -> LOG.errorf("Failed to update failed transaction: %s", ex.getMessage())
                                        );
                                
                                throw new AppException("Failed to process payment: " + e.getMessage(), 
                                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                            }
                        })
                        .subscribe().with(
                            emitter::complete,
                            emitter::fail
                        );
                    })
                );
    }

    @Override
    public Uni<BillingTransaction> processRecurringPayment(Business business, String subscriptionId) {
        LOG.infof("Processing recurring payment for business: %s with subscription: %s", 
                business.getId(), subscriptionId);
        
        // Get the subscription plan details
        SubscriptionPlan plan = business.getSubscriptionPlanEnum();
        int amountInCents = plan.getPriceInCents();
        String description = plan.name() + " Plan - Monthly Subscription";
        
        // Create metadata with subscription ID
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("subscriptionId", subscriptionId);
        metadata.put("isRecurring", true);
        
        // Process the payment using the business's payment method
        return processPayment(
                business,
                business.getPaymentMethodId(),
                amountInCents,
                description,
                metadata
        );
    }

    @Override
    public Uni<BillingTransaction> issueRefund(String transactionId, int amountInCents, String reason) {
        LOG.infof("Issuing refund of $%.2f for transaction: %s with reason: %s", 
                amountInCents / 100.0, transactionId, reason);
        
        // First, get the original transaction
        return billingRepository.getTransaction(transactionId)
                .flatMap(transactionOpt -> {
                    if (transactionOpt.isEmpty()) {
                        return Uni.createFrom().failure(
                                new AppException("Transaction not found: " + transactionId, 
                                        AppException.ErrorType.RESOURCE_ERROR));
                    }
                    
                    BillingTransaction transaction = transactionOpt.get();
                    String chargeId = transaction.getExternalPaymentId();
                    
                    if (chargeId == null) {
                        return Uni.createFrom().failure(
                                new AppException("Transaction has no external payment ID: " + transactionId, 
                                        AppException.ErrorType.VALIDATION_ERROR));
                    }
                    
                    // Create a pending refund transaction
                    BillingTransaction pendingRefund = new BillingTransaction(
                            transaction.getBusinessId(),
                            BillingTransaction.TransactionType.REFUND,
                            BillingTransaction.TransactionStatus.PENDING,
                            amountInCents,
                            "Refund: " + reason,
                            transaction.getPaymentMethodId(),
                            null, // externalPaymentId will be set after processing
                            Map.of(
                                "originalTransactionId", transactionId,
                                "reason", reason
                            )
                    );
                    
                    return billingRepository.createTransaction(pendingRefund)
                            .flatMap(refundTransaction -> 
                                Uni.createFrom().emitter(emitter -> {
                                    vertx.executeBlocking(() -> {
                                        try {
                                            // Create the refund in Stripe
                                            RefundCreateParams params = RefundCreateParams.builder()
                                                .setCharge(chargeId)
                                                .setAmount((long) amountInCents)
                                                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                                                .build();
                                            
                                            Refund refund = Refund.create(params);
                                            LOG.infof("Created refund: %s for charge: %s", 
                                                    refund.getId(), chargeId);
                                            
                                            // Update refund transaction with refund ID and status
                                            BillingTransaction completedRefund = refundTransaction
                                                    .withExternalPaymentId(refund.getId())
                                                    .withStatus(BillingTransaction.TransactionStatus.COMPLETED);
                                            
                                            // Also update the original transaction to refunded status
                                            BillingTransaction refundedTransaction = transaction
                                                    .withStatus(BillingTransaction.TransactionStatus.REFUNDED);
                                            
                                            // Update both transactions
                                            billingRepository.updateTransaction(refundedTransaction)
                                                    .subscribe().with(
                                                        v -> LOG.infof("Updated original transaction: %s to refunded", transactionId),
                                                        e -> LOG.errorf("Failed to update original transaction: %s", e.getMessage())
                                                    );
                                            
                                            return billingRepository.updateTransaction(completedRefund)
                                                    .await().indefinitely();
                                        } catch (StripeException e) {
                                            LOG.errorf("Failed to issue refund: %s", e.getMessage());
                                            
                                            // Update refund transaction with failed status
                                            BillingTransaction failedRefund = refundTransaction
                                                    .withStatus(BillingTransaction.TransactionStatus.FAILED);
                                            
                                            billingRepository.updateTransaction(failedRefund)
                                                    .subscribe().with(
                                                        v -> {}, 
                                                        ex -> LOG.errorf("Failed to update failed refund: %s", ex.getMessage())
                                                    );
                                            
                                            throw new AppException("Failed to issue refund: " + e.getMessage(), 
                                                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                                        }
                                    })
                                    .subscribe().with(
                                        emitter::complete,
                                        emitter::fail
                                    );
                                })
                            );
                });
    }

    /**
     * Helper method to get or create a Stripe customer for a business.
     * 
     * @param businessId The business ID
     * @return The Stripe customer
     * @throws StripeException If there's an error communicating with Stripe
     */
    private Customer getOrCreateCustomer(String businessId) throws StripeException {
        LOG.debugf("Getting or creating Stripe customer for business: %s", businessId);
        
        // First, check if the business already has a Stripe customer ID in metadata
        Optional<String> customerId = getCustomerId(businessId);
        
        if (customerId.isPresent()) {
            try {
                // Try to retrieve the existing customer
                Customer customer = Customer.retrieve(customerId.get());
                LOG.debugf("Found existing Stripe customer: %s for business: %s", 
                        customer.getId(), businessId);
                return customer;
            } catch (StripeException e) {
                LOG.warnf("Could not retrieve Stripe customer: %s, will create new one. Error: %s", 
                        customerId.get(), e.getMessage());
                // Continue to create a new customer if the existing one couldn't be retrieved
            }
        }
        
        // Create a new customer
        CustomerCreateParams params = CustomerCreateParams.builder()
            .setDescription("Business: " + businessId)
            .putMetadata("businessId", businessId)
            .build();
        
        Customer customer = Customer.create(params);
        LOG.infof("Created new Stripe customer: %s for business: %s", 
                customer.getId(), businessId);
        
        // Store the customer ID in the business metadata
        storeCustomerId(businessId, customer.getId());
        
        return customer;
    }
    
    /**
     * Helper method to get the Stripe customer ID from business metadata.
     * 
     * @param businessId The business ID
     * @return Optional containing the customer ID if it exists
     */
    private Optional<String> getCustomerId(String businessId) {
        return userRepository.getBusiness(businessId)
            .map(businessOpt -> {
                if (businessOpt.isEmpty() || businessOpt.get().getMetadata() == null) {
                    return Optional.<String>empty();
                }
                
                Object customerId = businessOpt.get().getMetadata().get("stripeCustomerId");
                return customerId != null 
                        ? Optional.of(customerId.toString()) 
                        : Optional.<String>empty();
            })
            .await().indefinitely();
    }
    
    /**
     * Helper method to store the Stripe customer ID in business metadata.
     * 
     * @param businessId The business ID
     * @param customerId The Stripe customer ID to store
     */
    private void storeCustomerId(String businessId, String customerId) {
        userRepository.getBusiness(businessId)
            .flatMap(businessOpt -> {
                if (businessOpt.isEmpty()) {
                    LOG.warnf("Business %s not found when storing customer ID", businessId);
                    return Uni.createFrom().voidItem();
                }
                
                Business business = businessOpt.get();
                
                // Update business metadata with customer ID
                Map<String, Object> metadata = business.getMetadata() != null 
                        ? new HashMap<>(business.getMetadata()) 
                        : new HashMap<>();
                metadata.put("stripeCustomerId", customerId);
                
                Business updatedBusiness = new Business(
                        business.getId(),
                        business.getName(),
                        business.getSubscriptionPlan(),
                        business.getSubscriptionStatus(),
                        business.getSubscriptionStartDate(),
                        business.getNextBillingDate(),
                        business.getPaymentMethodId(),
                        business.getCreatedAt(),
                        Instant.now(),
                        business.getSettings(),
                        metadata,
                        business.getCurrentConnectorUsageBytes(),
                        business.getDocumentsProcessedThisMonth(),
                        business.getQueriesThisMonth()
                );
                
                return userRepository.saveBusiness(updatedBusiness);
            })
            .subscribe().with(
                v -> LOG.debugf("Stored Stripe customer ID %s for business %s", customerId, businessId),
                e -> LOG.errorf("Failed to store Stripe customer ID: %s", e.getMessage())
            );
    }
}