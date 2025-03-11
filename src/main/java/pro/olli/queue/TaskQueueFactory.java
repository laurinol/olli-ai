/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import com.google.cloud.tasks.v2.CloudTasksClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TaskQueueFactory {

    private final CloudTasksClient cloudTasksClient;
    private RabbitMqTaskService rabbitMqTaskService; // For cleanup

    @ConfigProperty(name = "queue.provider", defaultValue = "google")
    String queueProvider;

    public TaskQueueFactory(CloudTasksClient cloudTasksClient) {
        this.cloudTasksClient = cloudTasksClient;
    }

    @Produces
    @ApplicationScoped
    public TaskQueueService createTaskQueueService() {
        return switch (queueProvider.toLowerCase()) {
            case "google" -> new GoogleCloudTasksService(cloudTasksClient);
            case "rabbitmq" -> {
                this.rabbitMqTaskService = new RabbitMqTaskService();
                yield rabbitMqTaskService;
            }
            default -> throw new IllegalArgumentException("Unknown queue provider: " + queueProvider);
        };
    }

    // Cleanup for RabbitMQ
    public void shutdown() {
        if (rabbitMqTaskService != null) {
            rabbitMqTaskService.cleanup();
        }
    }
}
