/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

// No @ApplicationScoped - plain class
public class RabbitMqTaskService implements TaskQueueService {

    private static final Logger LOG = Logger.getLogger(RabbitMqTaskService.class);

    @ConfigProperty(name = "rabbitmq.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "rabbitmq.port", defaultValue = "5672")
    int port;

    @ConfigProperty(name = "rabbitmq.queue", defaultValue = "sync-queue")
    String queue;

    private Connection connection;
    private Channel channel;

    public RabbitMqTaskService() {
        init(); // Move init to constructor since no @PostConstruct
    }

    private void init() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            this.connection = factory.newConnection();
            this.channel = connection.createChannel();
            channel.queueDeclare(queue, true, false, false, null);
            LOG.infof("RabbitMQ connection initialized at %s:%d", host, port);
        } catch (IOException | TimeoutException e) {
            LOG.errorf("Failed to initialize RabbitMQ: %s", e.getMessage());
            throw new RuntimeException("RabbitMQ init failed", e);
        }
    }

    public void cleanup() { // Manual cleanup method
        try {
            if (channel != null) channel.close();
            if (connection != null) connection.close();
            LOG.info("RabbitMQ connection closed");
        } catch (IOException | TimeoutException e) {
            LOG.warnf("Error closing RabbitMQ: %s", e.getMessage());
        }
    }

    @Override
    public Uni<Void> enqueueTask(String taskId, String jsonPayload) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                channel.basicPublish("", queue, null, jsonPayload.getBytes(StandardCharsets.UTF_8));
                LOG.infof("Enqueued task %s to RabbitMQ", taskId);
                emitter.complete(null);
            } catch (IOException e) {
                LOG.errorf("Failed to enqueue task %s to RabbitMQ: %s", taskId, e.getMessage());
                emitter.fail(e);
            }
        });
    }
}
