/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.models;

import java.time.Instant;
import java.util.Objects;

public class SyncHistoryEntry {
    private final String taskId;
    private final Instant timestamp;
    private final String provider;
    private final String path;
    private final int filesProcessed;
    private final String status;
    private final String error;

    // For JSON/serialization frameworks
    protected SyncHistoryEntry() {
        this(null, null, null, null, 0, null, null);
    }
    
    public SyncHistoryEntry(String taskId, Instant timestamp, String provider, String path, 
                           int filesProcessed, String status, String error) {
        this.taskId = taskId;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.provider = provider;
        this.path = path;
        this.filesProcessed = filesProcessed;
        this.status = status;
        this.error = error;
    }
    
    // No-taskId constructor for backward compatibility
    public SyncHistoryEntry(Instant timestamp, String provider, String path, 
                           int filesProcessed, String status, String error) {
        this(null, timestamp, provider, path, filesProcessed, status, error);
    }
    
    // Create a new entry with updated status
    public SyncHistoryEntry withStatus(String newStatus) {
        return new SyncHistoryEntry(this.taskId, this.timestamp, this.provider, this.path, 
                                  this.filesProcessed, newStatus, this.error);
    }
    
    // Create a new entry with error information
    public SyncHistoryEntry withError(String errorMessage) {
        return new SyncHistoryEntry(this.taskId, this.timestamp, this.provider, this.path, 
                                  this.filesProcessed, this.status, errorMessage);
    }
    
    // Create a new entry with updated file count
    public SyncHistoryEntry withFilesProcessed(int newCount) {
        return new SyncHistoryEntry(this.taskId, this.timestamp, this.provider, this.path, 
                                  newCount, this.status, this.error);
    }
    
    // Getters
    public String getTaskId() {
        return taskId;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public String getProvider() {
        return provider;
    }
    
    public String getPath() {
        return path;
    }
    
    public int getFilesProcessed() {
        return filesProcessed;
    }
    
    public String getStatus() {
        return status;
    }
    
    public String getError() {
        return error;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncHistoryEntry that = (SyncHistoryEntry) o;
        return filesProcessed == that.filesProcessed &&
               Objects.equals(taskId, that.taskId) &&
               Objects.equals(timestamp, that.timestamp) &&
               Objects.equals(provider, that.provider) &&
               Objects.equals(path, that.path) &&
               Objects.equals(status, that.status);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(taskId, timestamp, provider, path, filesProcessed, status);
    }
}
