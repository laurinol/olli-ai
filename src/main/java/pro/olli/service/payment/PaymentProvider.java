/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.service.payment;

import io.smallrye.mutiny.Uni;
import pro.olli.models.BillingTransaction;
import pro.olli.models.Business;

import java.util.Map;

/**
 * Interface for payment providers that handle recurring credit card charges
 * and other payment operations.
 */
public interface PaymentProvider {

    /**
     * Creates a payment method (card, bank account, etc.) for a business.
     *
     * @param businessId       The business ID
     * @param paymentDetails   Map containing payment details (card number, expiry, etc.)
     * @return A Uni containing the payment method ID
     */
    Uni<String> createPaymentMethod(String businessId, Map<String, Object> paymentDetails);

    /**
     * Updates an existing payment method.
     *
     * @param paymentMethodId  The payment method ID to update
     * @param paymentDetails   Map containing updated payment details
     * @return A Uni containing the updated payment method ID
     */
    Uni<String> updatePaymentMethod(String paymentMethodId, Map<String, Object> paymentDetails);

    /**
     * Retrieves information about a payment method.
     * 
     * @param paymentMethodId  The payment method ID
     * @return A Uni containing payment method details
     */
    Uni<Map<String, Object>> getPaymentMethod(String paymentMethodId);

    /**
     * Creates a subscription for a business.
     *
     * @param business         The business to create a subscription for
     * @param paymentMethodId  The payment method ID to use
     * @return A Uni containing the subscription ID
     */
    Uni<String> createSubscription(Business business, String paymentMethodId);

    /**
     * Updates an existing subscription.
     *
     * @param business         The business with updated subscription details
     * @param subscriptionId   The subscription ID to update
     * @return A Uni containing the updated subscription ID
     */
    Uni<String> updateSubscription(Business business, String subscriptionId);

    /**
     * Cancels a subscription.
     *
     * @param subscriptionId   The subscription ID to cancel
     * @param immediateEffect  Whether to cancel immediately or at the end of billing period
     * @return A Uni containing a status message
     */
    Uni<String> cancelSubscription(String subscriptionId, boolean immediateEffect);

    /**
     * Process a one-time payment.
     *
     * @param business         The business to charge
     * @param paymentMethodId  The payment method ID to use
     * @param amountInCents    The amount to charge in cents
     * @param description      Description of the charge
     * @param metadata         Additional metadata for the transaction
     * @return A Uni containing the created transaction
     */
    Uni<BillingTransaction> processPayment(
            Business business,
            String paymentMethodId,
            int amountInCents,
            String description,
            Map<String, Object> metadata);

    /**
     * Process a recurring payment for a subscription.
     *
     * @param business        The business to charge
     * @param subscriptionId  The subscription ID
     * @return A Uni containing the created transaction
     */
    Uni<BillingTransaction> processRecurringPayment(Business business, String subscriptionId);

    /**
     * Issues a refund for a transaction.
     *
     * @param transactionId   The transaction ID to refund
     * @param amountInCents   The amount to refund in cents (may be partial)
     * @param reason          Reason for the refund
     * @return A Uni containing the refund transaction
     */
    Uni<BillingTransaction> issueRefund(
            String transactionId,
            int amountInCents,
            String reason);
}