/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.models;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class User {

    private final String id;
    private final String email;
    private final String name;
    private final String avatarUrl;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Map<String, Object> metadata;

    // For auth process only - not persisted
    private final String password;

    // For JSON/serialization frameworks
    protected User() {
        this(null, null, null, null, null, null, null, null);
    }

    public User(String id, String email, String name) {
        this(id, email, name, null, Instant.now(), Instant.now(), null, null);
    }

    public User(String id, String email, String name, String avatarUrl,
        Instant createdAt, Instant updatedAt,
        Map<String, Object> metadata, String password) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.metadata = metadata;
        this.password = password;
    }

    // Immutable object with new instance creation for updates
    public User withUpdatedTime() {
        return new User(id, email, name, avatarUrl, createdAt, Instant.now(), metadata, password);
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        User user = (User) o;
        return Objects.equals(id, user.id) &&
            Objects.equals(email, user.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email);
    }
}
