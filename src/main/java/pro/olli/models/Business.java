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
    private final String planLevel; // e.g., "basic", "premium", "enterprise"
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Map<String, Object> settings;
    private final Map<String, Object> metadata;
    
    // For JSON/serialization frameworks
    protected Business() {
        this(null, null, null, null, null, null, null);
    }
    
    public Business(String id, String name, String planLevel) {
        this(id, name, planLevel, Instant.now(), Instant.now(), null, null);
    }
    
    public Business(String id, String name, String planLevel,
                   Instant createdAt, Instant updatedAt,
                   Map<String, Object> settings, Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        this.planLevel = planLevel;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.settings = settings != null ? Collections.unmodifiableMap(settings) : null;
        this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : null;
    }
    
    // Immutable object with new instance creation for updates
    public Business withUpdatedTime() {
        return new Business(id, name, planLevel, createdAt, Instant.now(), settings, metadata);
    }
    
    public Business withPlanLevel(String newPlanLevel) {
        return new Business(id, name, newPlanLevel, createdAt, Instant.now(), settings, metadata);
    }
    
    // Getters
    public String getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }
    
    public String getPlanLevel() {
        return planLevel;
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