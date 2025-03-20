/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified model for connector provider credentials.
 * Stores key-value pairs to accommodate diverse authentication mechanisms (e.g., OAuth tokens, SSH keys, IAM keys).
 */
public class ConnectorCredentials {

    private final Map<String, Object> credentials;

    /**
     * Constructs credentials from a map.
     * @param credentials Map of credential key-value pairs (e.g., {"accessToken": "..."}, {"sshKey": "..."}).
     */
    public ConnectorCredentials(Map<String, Object> credentials) {
        this.credentials = new HashMap<>(credentials != null ? credentials : new HashMap<>());
    }

    /**
     * Gets a specific credential value by key.
     * @param key The credential key (e.g., "username", "accessToken").
     * @return The value, or null if not present.
     */
    public Object get(String key) {
        return credentials.get(key);
    }

    /**
     * Returns the full credential map (immutable copy for safety).
     * @return Map of all credentials.
     */
    public Map<String, Object> getCredentials() {
        return new HashMap<>(credentials); // Defensive copy
    }
}
