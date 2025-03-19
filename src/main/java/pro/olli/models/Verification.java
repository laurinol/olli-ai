/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an email verification for user registration.
 * This model is for verifying user email addresses during account setup.
 */
public class Verification {
    private final String token;
    private final String businessId;
    private final String email;
    private final String role; // e.g., "admin", "user", "viewer"
    private final Instant createdAt;
    private final Instant expiresAt;
    private final boolean verified;
    
    // For JSON/serialization frameworks
    protected Verification() {
        this(null, null, null, null, null, null, false);
    }
    
    public Verification(String token, String businessId, String email, String role) {
        this(token, businessId, email, role, Instant.now(), 
            // 48 hour expiration for email verification (more appropriate than 7 days)
            Instant.now().plusSeconds(48 * 60 * 60), false);
    }
    
    public Verification(String token, String businessId, String email, String role,
                     Instant createdAt, Instant expiresAt, boolean verified) {
        this.token = token;
        this.businessId = businessId;
        this.email = email;
        this.role = role;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.verified = verified;
    }
    
    // Create a new verified instance
    public Verification withVerified() {
        return new Verification(token, businessId, email, role,
                               createdAt, expiresAt, true);
    }
    
    // Getters
    public String getToken() {
        return token;
    }
    
    public String getBusinessId() {
        return businessId;
    }
    
    public String getEmail() {
        return email;
    }
    
    public String getRole() {
        return role;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public boolean isVerified() {
        return verified;
    }
    
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Verification verification = (Verification) o;
        return Objects.equals(token, verification.token);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(token);
    }
}