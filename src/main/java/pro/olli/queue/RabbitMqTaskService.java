/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.models.DequeuedTask;

public class RabbitMqTaskService implements TaskQueueService {

    private static final Logger LOG = Logger.getLogger(RabbitMqTaskService.class);

    private Connection connection;
    private Channel channel;
    private final String queue;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    public RabbitMqTaskService(String host, int port, String username, String password, String queue) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.queue = queue;
        connect();
    }

    private void connect() {
        try {
            LOG.infof("Attempting to connect to RabbitMQ at %s:%d with username '%s' and password '%s'", 
                     host, port, username, password);
            
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(username);
            factory.setPassword(password);
            
            LOG.infof("Created ConnectionFactory, attempting to open connection...");
            this.connection = factory.newConnection();
            LOG.infof("RabbitMQ connection established successfully");
            
            this.channel = connection.createChannel();
            LOG.infof("RabbitMQ channel created successfully");
            
            channel.queueDeclare(queue, true, false, false, null);
            LOG.infof("RabbitMQ queue '%s' declared/confirmed", queue);
            
            LOG.infof("RabbitMQ connection fully initialized at %s:%d, queue: %s", host, port, queue);
        } catch (IOException | TimeoutException e) {
            LOG.errorf("Failed to initialize RabbitMQ: %s", e.getMessage());
            LOG.errorf("Connection details - host: %s, port: %d, username: %s, queue: %s", 
                      host, port, username, queue);
            e.printStackTrace(); // Print full stack trace for debugging
            throw new RuntimeException("RabbitMQ init failed", e);
        }
    }

    public void cleanup() { // Manual cleanup method
        try {
            if (channel != null) {
                channel.close();
            }
            if (connection != null) {
                connection.close();
            }
            LOG.info("RabbitMQ connection closed");
        } catch (IOException | TimeoutException e) {
            LOG.warnf("Error closing RabbitMQ: %s", e.getMessage());
        }
    }

    @Override
    public Uni<Void> enqueueTask(String taskId, String jsonPayload) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .messageId(taskId)
                    .build();
                channel.basicPublish("", queue, properties, jsonPayload.getBytes(StandardCharsets.UTF_8));
                LOG.infof("Enqueued task %s to RabbitMQ", taskId);
                emitter.complete(null);
            } catch (IOException e) {
                LOG.errorf("Failed to enqueue task %s to RabbitMQ: %s", taskId, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<DequeuedTask> dequeueTask() {
        return Uni.createFrom().emitter(emitter -> {
            try {
                GetResponse response = channel.basicGet(queue, true); // Auto-ack
                if (response != null) {
                    String taskId = response.getProps().getMessageId();
                    String jsonPayload = new String(response.getBody(), StandardCharsets.UTF_8);
                    LOG.infof("Dequeued task %s from RabbitMQ: %s", taskId, jsonPayload);
                    emitter.complete(new DequeuedTask(taskId, jsonPayload));
                } else {
                    emitter.fail(new IOException("No tasks available in RabbitMQ queue: " + queue));
                }
            } catch (IOException e) {
                LOG.errorf("Failed to dequeue task from RabbitMQ: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<java.util.Optional<DequeuedTask>> peekTask(String taskId) {
        // This is a mock implementation for testing - in a real system we would need
        // a more complex implementation that doesn't consume the message
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Since RabbitMQ doesn't easily support peek, we'll implement it
                // as a get followed by a re-queue for testing purposes
                GetResponse response = channel.basicGet(queue, false); // Don't auto-ack
                if (response != null) {
                    String messageId = response.getProps().getMessageId();
                    String jsonPayload = new String(response.getBody(), StandardCharsets.UTF_8);

                    // Always requeue the message so we don't consume it
                    channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);

                    // Return the task if it matches the ID
                    if (messageId.equals(taskId)) {
                        emitter.complete(java.util.Optional.of(new DequeuedTask(messageId, jsonPayload)));
                    } else {
                        emitter.complete(java.util.Optional.empty());
                    }
                } else {
                    emitter.complete(java.util.Optional.empty());
                }
            } catch (IOException e) {
                LOG.errorf("Failed to peek task %s from RabbitMQ: %s", taskId, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<DequeuedTask> dequeueSpecificTask(String taskId) {
        // For testing purposes - this is not an efficient implementation
        // In a real system we would use a more sophisticated approach
        return Uni.createFrom().emitter(emitter -> {
            try {
                GetResponse response = channel.basicGet(queue, false); // Don't auto-ack
                if (response != null) {
                    String messageId = response.getProps().getMessageId();
                    String jsonPayload = new String(response.getBody(), StandardCharsets.UTF_8);

                    if (messageId.equals(taskId)) {
                        // Acknowledge this message since it matches
                        channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                        LOG.infof("Dequeued specific task %s from RabbitMQ", taskId);
                        emitter.complete(new DequeuedTask(messageId, jsonPayload));
                    } else {
                        // Requeue this message since it doesn't match
                        channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
                        emitter.fail(new IOException("Task " + taskId + " not found in queue"));
                    }
                } else {
                    emitter.fail(new IOException("No tasks available in RabbitMQ queue: " + queue));
                }
            } catch (IOException e) {
                LOG.errorf("Failed to dequeue specific task %s from RabbitMQ: %s", taskId, e.getMessage());
                emitter.fail(e);
            }
        });
    }
}
