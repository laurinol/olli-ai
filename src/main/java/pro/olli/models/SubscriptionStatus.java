/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.models;

/**
 * Enum representing the current status of a business subscription.
 */
public enum SubscriptionStatus {
    ACTIVE("active"),
    TRIAL("trial"),
    PAST_DUE("past_due"),
    CANCELED("canceled"),
    EXPIRED("expired");

    private final String value;

    SubscriptionStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SubscriptionStatus fromString(String value) {
        for (SubscriptionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return EXPIRED; // Default to EXPIRED if unknown
    }
}