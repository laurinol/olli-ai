/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.models;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Model representing a billing transaction for a business.
 */
public class BillingTransaction {
    
    public enum TransactionType {
        SUBSCRIPTION_PAYMENT,
        REFUND,
        PLAN_CHANGE,
        TRIAL_START,
        TRIAL_CONVERSION,
        FAILED_PAYMENT
    }
    
    public enum TransactionStatus {
        PENDING,
        COMPLETED,
        FAILED,
        REFUNDED
    }
    
    private final String id;
    private final String businessId;
    private final TransactionType type;
    private final TransactionStatus status;
    private final int amountInCents;
    private final String description;
    private final String paymentMethodId;
    private final String externalPaymentId;
    private final Instant createdAt;
    private final Map<String, Object> metadata;
    
    // For serialization
    protected BillingTransaction() {
        this(null, null, null, null, 0, null, null, null, null, null);
    }
    
    public BillingTransaction(
            String businessId,
            TransactionType type,
            TransactionStatus status,
            int amountInCents,
            String description,
            String paymentMethodId,
            String externalPaymentId,
            Map<String, Object> metadata) {
        
        this(UUID.randomUUID().toString(), businessId, type, status, amountInCents,
                description, paymentMethodId, externalPaymentId, Instant.now(), metadata);
    }
    
    public BillingTransaction(
            String id,
            String businessId,
            TransactionType type,
            TransactionStatus status,
            int amountInCents,
            String description,
            String paymentMethodId,
            String externalPaymentId,
            Instant createdAt,
            Map<String, Object> metadata) {
        
        this.id = id;
        this.businessId = businessId;
        this.type = type;
        this.status = status;
        this.amountInCents = amountInCents;
        this.description = description;
        this.paymentMethodId = paymentMethodId;
        this.externalPaymentId = externalPaymentId;
        this.createdAt = createdAt;
        this.metadata = metadata;
    }
    
    public BillingTransaction withStatus(TransactionStatus newStatus) {
        return new BillingTransaction(
                id, businessId, type, newStatus, amountInCents,
                description, paymentMethodId, externalPaymentId, createdAt, metadata);
    }
    
    public BillingTransaction withExternalPaymentId(String newExternalPaymentId) {
        return new BillingTransaction(
                id, businessId, type, status, amountInCents,
                description, paymentMethodId, newExternalPaymentId, createdAt, metadata);
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getBusinessId() {
        return businessId;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public int getAmountInCents() {
        return amountInCents;
    }

    public String getDescription() {
        return description;
    }

    public String getPaymentMethodId() {
        return paymentMethodId;
    }

    public String getExternalPaymentId() {
        return externalPaymentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    /**
     * Format amount as dollars
     */
    public double getAmountInDollars() {
        return amountInCents / 100.0;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BillingTransaction that = (BillingTransaction) o;
        return Objects.equals(id, that.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
