/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an invitation for a user to join a business team.
 * This model is for team invitations, which is separate from the email verification system.
 */
public class Invitation {
    private final String id;
    private final String businessId;
    private final String email;
    private final String role;
    private final String sentByUserId;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final boolean accepted;
    
    // For JSON/serialization frameworks
    protected Invitation() {
        this(null, null, null, null, null, null, null, false);
    }
    
    public Invitation(String id, String businessId, String email, String role, String sentByUserId) {
        this(id, businessId, email, role, sentByUserId, Instant.now(), 
             // 7 day expiration for team invitations
             Instant.now().plusSeconds(7 * 24 * 60 * 60), false);
    }
    
    public Invitation(String id, String businessId, String email, String role, String sentByUserId,
                   Instant createdAt, Instant expiresAt, boolean accepted) {
        this.id = id;
        this.businessId = businessId;
        this.email = email;
        this.role = role;
        this.sentByUserId = sentByUserId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.accepted = accepted;
    }
    
    // Create a new accepted instance
    public Invitation withAccepted() {
        return new Invitation(id, businessId, email, role, sentByUserId,
                           createdAt, expiresAt, true);
    }
    
    // Getters
    public String getId() {
        return id;
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
    
    public String getSentByUserId() {
        return sentByUserId;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public boolean isAccepted() {
        return accepted;
    }
    
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Invitation invitation = (Invitation) o;
        return Objects.equals(id, invitation.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}