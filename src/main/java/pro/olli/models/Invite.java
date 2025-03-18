/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

import java.time.Instant;
import java.util.Objects;

public class Invite {
    private final String token;
    private final String businessId;
    private final String email;
    private final String role; // e.g., "admin", "user", "viewer"
    private final String invitedBy; // User ID who created the invite
    private final Instant createdAt;
    private final Instant expiresAt;
    private final boolean claimed;
    
    // For JSON/serialization frameworks
    protected Invite() {
        this(null, null, null, null, null, null, null, false);
    }
    
    public Invite(String token, String businessId, String email, String role, String invitedBy) {
        this(token, businessId, email, role, invitedBy, Instant.now(), 
            Instant.now().plusSeconds(7 * 24 * 60 * 60), false);
    }
    
    public Invite(String token, String businessId, String email, String role, String invitedBy,
                 Instant createdAt, Instant expiresAt, boolean claimed) {
        this.token = token;
        this.businessId = businessId;
        this.email = email;
        this.role = role;
        this.invitedBy = invitedBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.claimed = claimed;
    }
    
    // Create a new claimed instance
    public Invite withClaimed() {
        return new Invite(token, businessId, email, role, invitedBy,
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
    
    public String getInvitedBy() {
        return invitedBy;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public boolean isClaimed() {
        return claimed;
    }
    
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Invite invite = (Invite) o;
        return Objects.equals(token, invite.token);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(token);
    }
}