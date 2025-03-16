/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.models;

public class DequeuedTask {
    private final String taskId;
    private final String jsonPayload;

    public DequeuedTask(String taskId, String jsonPayload) {
        this.taskId = taskId;
        this.jsonPayload = jsonPayload;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getJsonPayload() {
        return jsonPayload;
    }
}
