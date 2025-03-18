/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.admin;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.QueueName;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.repository.Factory;
import pro.olli.repository.StatsRepository;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Path("/admin/stats")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class AdminStatsController {

    private static final Logger LOG = Logger.getLogger(AdminStatsController.class);

    private final StatsRepository statsRepository;
    private final CloudTasksClient cloudTasksClient;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "queue.provider", defaultValue = "google")
    String queueProvider;

    // RabbitMQ config (dev only)
    @ConfigProperty(name = "rabbitmq.host", defaultValue = "localhost")
    String rabbitMqHost;
    @ConfigProperty(name = "rabbitmq.port", defaultValue = "5672")
    int rabbitMqPort;
    @ConfigProperty(name = "rabbitmq.username", defaultValue = "guest")
    String rabbitMqUsername;
    @ConfigProperty(name = "rabbitmq.password", defaultValue = "guest")
    String rabbitMqPassword;
    @ConfigProperty(name = "rabbitmq.management.port", defaultValue = "15672")
    int rabbitMqManagementPort;

    // Google Cloud Tasks config (prod)
    @ConfigProperty(name = "gcp.project-id")
    String projectId;
    @ConfigProperty(name = "gcp.location-id", defaultValue = "us-central1")
    String locationId;
    @ConfigProperty(name = "gcp.queue-id", defaultValue = "chat-requests")
    String queueId;

    public AdminStatsController(@Factory StatsRepository statsRepository, CloudTasksClient cloudTasksClient) {
        this.statsRepository = statsRepository;
        this.cloudTasksClient = cloudTasksClient;
    }

    @GET
    public Uni<Map<String, Object>> getStats() {
        Uni<Optional<Map<String, Object>>> firestoreStats = statsRepository.getGlobalStats();
        Uni<Integer> queueLength = "google".equalsIgnoreCase(queueProvider)
            ? getGoogleCloudTasksQueueLength()
            : getRabbitMqQueueLength();
        long thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli();

        return firestoreStats
            .flatMap(statsOpt -> {
                Map<String, Object> stats = new HashMap<>();
                statsOpt.ifPresent(stats::putAll);

                // Customer Metrics
                stats.putIfAbsent("totalCustomers", 0L);
                return statsRepository.getActiveCustomersCount(thirtyDaysAgo)
                    .map(active -> {
                        stats.put("activeCustomers", active);
                        return stats;
                    });
            })
            .flatMap(stats -> queueLength.map(length -> {
                stats.put("queueLength", length);

                // Chat Metrics
                stats.putIfAbsent("totalChatRequests", 0L);
                stats.put("chatRequestsPerSec", calculateChatRequestsPerSec(stats));
                stats.put("avgResponseTime", "1.2s"); // Placeholder

                // Document Processing Metrics
                stats.putIfAbsent("totalDocsProcessed", 0L);
                stats.put("docsPerHour", calculateDocsPerHour(stats));
                stats.putIfAbsent("processingErrors", 0L);

                // System Health
                stats.put("uptime", calculateUptime());
                stats.put("serverStatus", getServerStatus());

                // Business Metrics
                stats.put("estimatedMonthlyRevenue", calculateRevenue(stats));

                return stats;
            }))
            .onFailure().invoke(e -> LOG.errorf("Failed to fetch stats: %s", e.getMessage()));
    }

    private double calculateChatRequestsPerSec(Map<String, Object> stats) {
        return 2.5; // Placeholder
    }

    private long calculateDocsPerHour(Map<String, Object> stats) {
        return 150L; // Placeholder
    }

    private String calculateUptime() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long days = uptimeMs / (1000 * 60 * 60 * 24);
        long hours = (uptimeMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
        return String.format("%dd %dh", days, hours);
    }

    private Map<String, String> getServerStatus() {
        Map<String, String> status = new HashMap<>();
        status.put("Queue", "google".equalsIgnoreCase(queueProvider)
            ? checkGoogleCloudTasks() ? "✅" : "❌"
            : checkRabbitMq() ? "✅" : "❌");
        status.put("Firestore", statsRepository.checkConnectivity().await().indefinitely() ? "✅" : "❌");
        status.put("AI", "✅"); // Placeholder
        return status;
    }

    private Uni<Integer> getGoogleCloudTasksQueueLength() {
        return Uni.createFrom().emitter(emitter -> {
            try {
                QueueName queueName = QueueName.of(projectId, locationId, queueId);
                var response = cloudTasksClient.listTasks(queueName);
                int taskCount = 0;
                for (var task : response.iterateAll()) {
                    taskCount++;
                }
                emitter.complete(taskCount);
            } catch (Exception e) {
                LOG.errorf("Failed to fetch Google Cloud Tasks queue length: %s", e.getMessage());
                emitter.complete(0);
            }
        });
    }

    private Uni<Integer> getRabbitMqQueueLength() {
        String url = String.format("http://%s:%d/api/queues/%%2F/chat-requests",
            rabbitMqHost, rabbitMqManagementPort);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Basic " +
                java.util.Base64.getEncoder().encodeToString(
                    (rabbitMqUsername + ":" + rabbitMqPassword).getBytes()))
            .GET()
            .build();

        return Uni.createFrom().completionStage(
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return extractMessagesFromJson(response.body());
                    } else {
                        LOG.warnf("RabbitMQ API returned %d", response.statusCode());
                        return 0;
                    }
                })
                .exceptionally(throwable -> {
                    LOG.errorf("Failed to fetch RabbitMQ queue length: %s", throwable.getMessage());
                    return 0;
                }));
    }

    private int extractMessagesFromJson(String json) {
        String key = "\"messages\":";
        int start = json.indexOf(key) + key.length();
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);
        return Integer.parseInt(json.substring(start, end).trim());
    }

    private boolean checkGoogleCloudTasks() {
        try {
            QueueName queueName = QueueName.of(projectId, locationId, queueId);
            cloudTasksClient.getQueue(queueName);
            return true;
        } catch (Exception e) {
            LOG.errorf("Google Cloud Tasks check failed: %s", e.getMessage());
            return false;
        }
    }

    private boolean checkRabbitMq() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbitMqHost);
            factory.setPort(rabbitMqPort);
            factory.setUsername(rabbitMqUsername);
            factory.setPassword(rabbitMqPassword);
            Connection connection = factory.newConnection();
            connection.close();
            return true;
        } catch (IOException | TimeoutException e) {
            LOG.errorf("RabbitMQ check failed: %s", e.getMessage());
            return false;
        }
    }

    private String calculateRevenue(Map<String, Object> stats) {
        long customers = (Long) stats.getOrDefault("totalCustomers", 0L);
        return String.format("$%,d", customers * 99);
    }
}
