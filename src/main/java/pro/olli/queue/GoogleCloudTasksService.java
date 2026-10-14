/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.queue;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.ListTasksResponse;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import com.rabbitmq.client.GetResponse;
import io.smallrye.mutiny.Uni;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.models.DequeuedTask;

public class GoogleCloudTasksService implements TaskQueueService {

    private static final Logger LOG = Logger.getLogger(GoogleCloudTasksService.class);

    private final CloudTasksClient cloudTasksClient;

    @ConfigProperty(name = "gcp.project-id", defaultValue = "olli-ai")
    String projectId;

    @ConfigProperty(name = "gcp.tasks.location", defaultValue = "us-west1")
    String location;

    @ConfigProperty(name = "gcp.tasks.queue", defaultValue = "sync-queue")
    String queue;

    @ConfigProperty(name = "gcp.tasks.url", defaultValue = "http://localhost:8080/process")
    String processUrl;

    public GoogleCloudTasksService(CloudTasksClient cloudTasksClient) {
        this.cloudTasksClient = cloudTasksClient;
    }

    @Override
    public Uni<Void> enqueueTask(String taskId, String jsonPayload) {
        return Uni.createFrom().emitter(emitter -> {
            QueueName queueName = QueueName.of(projectId, location, queue);
            Task task = Task.newBuilder()
                .setHttpRequest(HttpRequest.newBuilder()
                    .setUrl(processUrl)
                    .setHttpMethod(HttpMethod.POST)
                    .setBody(ByteString.copyFrom(jsonPayload.getBytes(StandardCharsets.UTF_8)))
                    .build())
                .setName(queueName.toString() + "/tasks/" + taskId)
                .build();

            try {
                cloudTasksClient.createTask(queueName, task);
                LOG.infof("Enqueued task %s to Google Cloud Tasks", taskId);
                emitter.complete(null);
            } catch (Exception e) {
                LOG.errorf("Failed to enqueue task %s: %s", taskId, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    @Override
    public Uni<DequeuedTask> dequeueTask() {
        return Uni.createFrom().emitter(emitter -> {
            try {
                QueueName queueName = QueueName.of(projectId, location, queue);
                CloudTasksClient.ListTasksPagedResponse pagedResponse = cloudTasksClient.listTasks(queueName);
                Iterable<Task> tasks = pagedResponse.iterateAll();
                Iterator<Task> iterator = tasks.iterator();
                if (iterator.hasNext()) {
                    Task task = iterator.next();
                    String taskId = task.getName().substring(task.getName().lastIndexOf('/') + 1); // Extract taskId from name
                    String jsonPayload = task.getAppEngineHttpRequest().getBody().toStringUtf8();
                    cloudTasksClient.deleteTask(task.getName());
                    LOG.infof("Dequeued task %s from Google Cloud Tasks: %s", taskId, jsonPayload);
                    emitter.complete(new DequeuedTask(taskId, jsonPayload));
                } else {
                    emitter.fail(new IOException("No tasks available in Google Cloud Tasks queue: " + queue));
                }
            } catch (Exception e) {
                LOG.errorf("Failed to dequeue task from Google Cloud Tasks: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }
}
