/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.models;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class BusinessUser {
    private final String id;
    private final String businessId;
    private final String email;
    private final String name;
    private final String role; // e.g., "admin", "user", "viewer"
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<SyncHistoryEntry> syncHistory;
    private final Map<String, Object> preferences;
    
    // For JSON/serialization frameworks
    protected BusinessUser() {
        this(null, null, null, null, null, null, null, null, null);
    }
    
    public BusinessUser(String id, String businessId, String email, String name, String role) {
        this(id, businessId, email, name, role, Instant.now(), Instant.now(), 
             new ArrayList<>(), null);
    }
    
    public BusinessUser(String id, String businessId, String email, String name, String role,
                        Instant createdAt, Instant updatedAt, List<SyncHistoryEntry> syncHistory,
                        Map<String, Object> preferences) {
        this.id = id;
        this.businessId = businessId;
        this.email = email;
        this.name = name;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.syncHistory = syncHistory != null ? 
                        Collections.unmodifiableList(new ArrayList<>(syncHistory)) : 
                        Collections.emptyList();
        this.preferences = preferences != null ? 
                        Collections.unmodifiableMap(preferences) : 
                        null;
    }
    
    // Create a new instance with an updated sync history
    public BusinessUser withSyncHistoryEntry(SyncHistoryEntry entry) {
        List<SyncHistoryEntry> newHistory = new ArrayList<>(syncHistory);
        newHistory.add(entry);
        return new BusinessUser(id, businessId, email, name, role, 
                               createdAt, Instant.now(),
                               newHistory, preferences);
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
    
    public String getName() {
        return name;
    }
    
    public String getRole() {
        return role;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public List<SyncHistoryEntry> getSyncHistory() {
        return syncHistory;
    }
    
    public Map<String, Object> getPreferences() {
        return preferences;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BusinessUser that = (BusinessUser) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(businessId, that.businessId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, businessId);
    }
}
