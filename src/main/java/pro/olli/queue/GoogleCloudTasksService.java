/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.queue;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;

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
}
