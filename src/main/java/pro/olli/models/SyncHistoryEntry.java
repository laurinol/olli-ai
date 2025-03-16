/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.models;

public class SyncHistoryEntry {
    private String taskId;
    private String source;
    private String folderId;
    private String timestamp;
    private String status; // For SyncWorker
    private int fileCount; // For SyncWorker

    public SyncHistoryEntry() {} // For Gson/Firestore
    public SyncHistoryEntry(String taskId, String source, String folderId, String timestamp, String status, int fileCount) {
        this.taskId = taskId;
        this.source = source;
        this.folderId = folderId;
        this.timestamp = timestamp;
        this.status = status;
        this.fileCount = fileCount;
    }

    // Getters and setters
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getFolderId() { return folderId; }
    public void setFolderId(String folderId) { this.folderId = folderId; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }
}
