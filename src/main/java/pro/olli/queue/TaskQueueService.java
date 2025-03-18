/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import io.smallrye.mutiny.Uni;
import pro.olli.models.DequeuedTask;

public interface TaskQueueService {
    Uni<Void> enqueueTask(String taskId, String jsonPayload);
    Uni<DequeuedTask> dequeueTask();
    
    /**
     * Peeks at a specific task without removing it from the queue
     * For testing purposes
     * 
     * @param taskId Task ID to peek at
     * @return Optional with the task or empty if not found
     */
    default Uni<java.util.Optional<DequeuedTask>> peekTask(String taskId) {
        throw new UnsupportedOperationException("Not implemented");
    }
    
    /**
     * Dequeues a specific task by ID
     * For testing purposes
     * 
     * @param taskId Task ID to dequeue
     * @return The dequeued task
     */
    default Uni<DequeuedTask> dequeueSpecificTask(String taskId) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
