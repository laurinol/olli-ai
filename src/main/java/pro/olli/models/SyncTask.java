/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

public class SyncTask {
    private final  String clientId;
    private final String userId;
    private final String source;
    private final String folderId;

    public SyncTask(String clientId, String userId, String source, String folderId) {
        this.clientId = clientId;
        this.userId = userId;
        this.source = source;
        this.folderId = folderId;
    }

    public String getClientId() {
        return clientId;
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
