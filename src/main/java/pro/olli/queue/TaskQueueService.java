/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import io.smallrye.mutiny.Uni;

public interface TaskQueueService {
    Uni<Void> enqueueTask(String taskId, String jsonPayload);
}
