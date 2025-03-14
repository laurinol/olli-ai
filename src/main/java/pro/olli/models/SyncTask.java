/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

public class SyncTask {
    private final String businessId;
    private final String userId;
    private final String source;
    private final String folderId;

    public SyncTask(String businessId, String userId, String source, String folderId) {
        this.businessId = businessId;
        this.userId = userId;
        this.source = source;
        this.folderId = folderId;
    }

    public String getBusinessId() {
        return businessId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSource() {
        return source;
    }

    public String getFolderId() {
        return folderId;
    }
}
