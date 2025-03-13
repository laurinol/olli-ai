/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(username);
            factory.setPassword(password);
            this.connection = factory.newConnection();
            this.channel = connection.createChannel();
            channel.queueDeclare(queue, true, false, false, null);
            LOG.infof("RabbitMQ connection initialized at %s:%d, ($s)", host, port, queue);
        } catch (IOException | TimeoutException e) {
            LOG.errorf("Failed to initialize RabbitMQ: %s", e.getMessage());
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
