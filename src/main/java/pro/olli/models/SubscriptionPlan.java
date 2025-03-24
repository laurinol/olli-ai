/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.models;

/**
 * Enum representing available subscription plans for businesses.
 */
public enum SubscriptionPlan {
    FREE("free", 5, 500, 50, false, 0),
    BASIC("basic", 10, 1000, 200, false, 9900),
    PROFESSIONAL("professional", 50, 5000, 1000, true, 39900),
    ENTERPRISE("enterprise", 200, 20000, 5000, true, 149900);

    private final String value;
    private final int connectorLimitGB;
    private final int monthlyDocumentLimit;
    private final int monthlyQueryLimit;
    private final boolean enabledAdvancedFeatures;
    private final int priceInCents;

    SubscriptionPlan(String value, int connectorLimitGB, int monthlyDocumentLimit,
                     int monthlyQueryLimit, boolean enabledAdvancedFeatures, int priceInCents) {
        this.value = value;
        this.connectorLimitGB = connectorLimitGB;
        this.monthlyDocumentLimit = monthlyDocumentLimit;
        this.monthlyQueryLimit = monthlyQueryLimit;
        this.enabledAdvancedFeatures = enabledAdvancedFeatures;
        this.priceInCents = priceInCents;
    }

    public String getValue() {
        return value;
    }

    public int getConnectorLimitGB() {
        return connectorLimitGB;
    }

    public int getMonthlyDocumentLimit() {
        return monthlyDocumentLimit;
    }

    public int getMonthlyQueryLimit() {
        return monthlyQueryLimit;
    }

    public boolean isEnabledAdvancedFeatures() {
        return enabledAdvancedFeatures;
    }

    public int getPriceInCents() {
        return priceInCents;
    }

    public static SubscriptionPlan fromString(String value) {
        for (SubscriptionPlan plan : values()) {
            if (plan.value.equalsIgnoreCase(value)) {
                return plan;
            }
        }
        return FREE; // Default to FREE plan if unknown
    }
    
    /**
     * Find a subscription plan by its value string.
     */
    public static SubscriptionPlan fromValue(String value) {
        return fromString(value);
    }
}