/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import com.google.cloud.tasks.v2.CloudTasksClient;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Startup
public class TaskQueueFactory {

    private final CloudTasksClient cloudTasksClient;
    private RabbitMqTaskService rabbitMqTaskService; // For cleanup

    @ConfigProperty(name = "queue.provider", defaultValue = "google")
    String queueProvider;

    @ConfigProperty(name = "rabbitmq.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "rabbitmq.port", defaultValue = "5672")
    int port;

    @ConfigProperty(name = "rabbitmq.queue", defaultValue = "sync-queue")
    String queue;

    @ConfigProperty(name = "rabbitmq.username", defaultValue = "admin")
    String username;

    @ConfigProperty(name = "rabbitmq.password", defaultValue = "pass123")
    String password;

    public TaskQueueFactory(CloudTasksClient cloudTasksClient) {
        this.cloudTasksClient = cloudTasksClient;
    }

    @Produces
    @ApplicationScoped
    public TaskQueueService createTaskQueueService() {
        return switch (queueProvider.toLowerCase()) {
            case "google" -> new GoogleCloudTasksService(cloudTasksClient);
            case "rabbitmq" -> {
                this.rabbitMqTaskService = new RabbitMqTaskService(host, port, username, password, queue);
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
