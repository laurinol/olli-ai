/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class Business {
    private final String id;
    private final String name;
    private final String subscriptionPlan; // Maps to SubscriptionPlan enum value
    private final String subscriptionStatus; // Maps to SubscriptionStatus enum value
    private final Instant subscriptionStartDate;
    private final Instant nextBillingDate;
    private final String paymentMethodId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Map<String, Object> settings;
    private final Map<String, Object> metadata;
    
    // Usage tracking fields
    private final int currentStorageUsageBytes;
    private final int documentsProcessedThisMonth;
    private final int queriesThisMonth;
    
    // For JSON/serialization frameworks
    protected Business() {
        this(null, null, null, null, null, null, 
            null, null, null, null, null, 0, 0, 0);
    }
    
    public Business(String id, String name, String subscriptionPlan) {
        this(id, name, subscriptionPlan, SubscriptionStatus.TRIAL.getValue(), 
            Instant.now(), Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS), null, 
            Instant.now(), Instant.now(), null, null, 0, 0, 0);
    }
    
    public Business(String id, String name, String subscriptionPlan,
                   String subscriptionStatus, Instant subscriptionStartDate, 
                   Instant nextBillingDate, String paymentMethodId,
                   Instant createdAt, Instant updatedAt,
                   Map<String, Object> settings, Map<String, Object> metadata,
                   int currentStorageUsageBytes, int documentsProcessedThisMonth, int queriesThisMonth) {
        this.id = id;
        this.name = name;
        this.subscriptionPlan = subscriptionPlan;
        this.subscriptionStatus = subscriptionStatus;
        this.subscriptionStartDate = subscriptionStartDate;
        this.nextBillingDate = nextBillingDate;
        this.paymentMethodId = paymentMethodId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.settings = settings != null ? Collections.unmodifiableMap(settings) : null;
        this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : null;
        this.currentStorageUsageBytes = currentStorageUsageBytes;
        this.documentsProcessedThisMonth = documentsProcessedThisMonth;
        this.queriesThisMonth = queriesThisMonth;
    }
    
    // Immutable object with new instance creation for updates
    public Business withUpdatedTime() {
        return new Business(id, name, subscriptionPlan, subscriptionStatus, 
            subscriptionStartDate, nextBillingDate, paymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            currentStorageUsageBytes, documentsProcessedThisMonth, queriesThisMonth);
    }
    
    public Business withSubscriptionPlan(String newSubscriptionPlan) {
        return new Business(id, name, newSubscriptionPlan, subscriptionStatus, 
            subscriptionStartDate, nextBillingDate, paymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            currentStorageUsageBytes, documentsProcessedThisMonth, queriesThisMonth);
    }
    
    public Business withSubscriptionStatus(String newSubscriptionStatus) {
        return new Business(id, name, subscriptionPlan, newSubscriptionStatus, 
            subscriptionStartDate, nextBillingDate, paymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            currentStorageUsageBytes, documentsProcessedThisMonth, queriesThisMonth);
    }
    
    public Business withNextBillingDate(Instant newNextBillingDate) {
        return new Business(id, name, subscriptionPlan, subscriptionStatus, 
            subscriptionStartDate, newNextBillingDate, paymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            currentStorageUsageBytes, documentsProcessedThisMonth, queriesThisMonth);
    }
    
    public Business withPaymentMethodId(String newPaymentMethodId) {
        return new Business(id, name, subscriptionPlan, subscriptionStatus, 
            subscriptionStartDate, nextBillingDate, newPaymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            currentStorageUsageBytes, documentsProcessedThisMonth, queriesThisMonth);
    }
    
    public Business withUsageMetrics(int newStorageUsageBytes, int newDocumentsProcessed, int newQueries) {
        return new Business(id, name, subscriptionPlan, subscriptionStatus, 
            subscriptionStartDate, nextBillingDate, paymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            newStorageUsageBytes, newDocumentsProcessed, newQueries);
    }
    
    public Business incrementDocumentsProcessed(int count) {
        return new Business(id, name, subscriptionPlan, subscriptionStatus, 
            subscriptionStartDate, nextBillingDate, paymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            currentStorageUsageBytes, documentsProcessedThisMonth + count, queriesThisMonth);
    }
    
    public Business incrementQueries(int count) {
        return new Business(id, name, subscriptionPlan, subscriptionStatus, 
            subscriptionStartDate, nextBillingDate, paymentMethodId,
            createdAt, Instant.now(), settings, metadata,
            currentStorageUsageBytes, documentsProcessedThisMonth, queriesThisMonth + count);
    }
    
    // Getters
    public String getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public String getSubscriptionPlan() {
        return subscriptionPlan;
    }
    
    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }
    
    public Instant getSubscriptionStartDate() {
        return subscriptionStartDate;
    }
    
    public Instant getNextBillingDate() {
        return nextBillingDate;
    }
    
    public String getPaymentMethodId() {
        return paymentMethodId;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public Map<String, Object> getSettings() {
        return settings;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public int getCurrentStorageUsageBytes() {
        return currentStorageUsageBytes;
    }
    
    public int getDocumentsProcessedThisMonth() {
        return documentsProcessedThisMonth;
    }
    
    public int getQueriesThisMonth() {
        return queriesThisMonth;
    }
    
    /**
     * Convert storage from bytes to GB for limit comparison
     */
    public double getCurrentStorageUsageGB() {
        return currentStorageUsageBytes / (1024.0 * 1024.0 * 1024.0);
    }
    
    /**
     * Converts string subscription plan to enum
     */
    public SubscriptionPlan getSubscriptionPlanEnum() {
        return SubscriptionPlan.fromString(subscriptionPlan);
    }
    
    /**
     * Converts string subscription status to enum
     */
    public SubscriptionStatus getSubscriptionStatusEnum() {
        return SubscriptionStatus.fromString(subscriptionStatus);
    }
    
    /**
     * Check if storage limit is exceeded
     */
    public boolean isStorageLimitExceeded() {
        return getCurrentStorageUsageGB() > getSubscriptionPlanEnum().getStorageLimitGB();
    }
    
    /**
     * Check if document processing limit is exceeded
     */
    public boolean isDocumentLimitExceeded() {
        return documentsProcessedThisMonth >= getSubscriptionPlanEnum().getMonthlyDocumentLimit();
    }
    
    /**
     * Check if query limit is exceeded
     */
    public boolean isQueryLimitExceeded() {
        return queriesThisMonth >= getSubscriptionPlanEnum().getMonthlyQueryLimit();
    }
    
    /**
     * Check if subscription is active
     */
    public boolean isSubscriptionActive() {
        SubscriptionStatus status = getSubscriptionStatusEnum();
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Business business = (Business) o;
        return Objects.equals(id, business.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}