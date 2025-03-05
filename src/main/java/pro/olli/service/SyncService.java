package pro.olli.service;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.quarkus.runtime.configuration.ProfileManager;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.jboss.logging.Logger;
import pro.olli.model.StorageCredentials;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import pro.olli.storage.StorageProviderType;

/**
 * Service for synchronizing files across storage providers.
 * Uses StorageFactory to delegate provider-specific operations.
 */
@ApplicationScoped
public class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class);

    private final Firestore firestore;
    private final StorageFactory storageFactory;

    public SyncService(Firestore firestore, StorageFactory storageFactory) {
        this.firestore = firestore;
        this.storageFactory = storageFactory;
    }

    public Uni<String> sync(String clientId, String source, String directory) {
        StorageProviderType type = StorageProviderType.fromString(source);
        if (type == null) {
            return Uni.createFrom().item("Unsupported source: " + source);
        }

        StorageProvider provider = storageFactory.getProvider(source);
        return Uni.createFrom().future(firestore.collection("clients").document(clientId).get())
            .onItem().transformToUni(doc -> {
                if (!doc.exists()) {
                    return Uni.createFrom().item("Client not found: " + clientId);
                }
                StorageCredentials creds = new StorageCredentials((Map<String, Object>) doc.get("token"));
                return provider.authenticate(creds)
                    .flatMap(authSuccess -> {
                        if (!authSuccess) {
                            return Uni.createFrom().item("Authentication failed for " + source);
                        }
                        return enqueueSyncTask(clientId, source, directory);
                    });
            })
            .onFailure().recoverWithItem(throwable -> {
                LOG.errorf("Sync failed for client %s, source %s: %s", clientId, source, throwable.getMessage());
                return "Sync error: " + throwable.getMessage();
            });
    }

    private Uni<String> enqueueSyncTask(String clientId, String source, String directory) {
        return Uni.createFrom().emitter(emitter -> {
            try (CloudTasksClient tasksClient = CloudTasksClient.create()) {
                String projectId = ConfigUtils.getProfiles().contains("dev") ? "local-emulator" : "your-gcp-project";
                String location = "us-central1";
                String queueName = "sync-queue";

                QueueName parent = QueueName.of(projectId, location, queueName);
                String payload = String.format("{\"clientId\":\"%s\",\"source\":\"%s\",\"folderId\":\"%s\"}",
                    clientId, source, directory != null ? directory : "");
                Task.Builder taskBuilder = Task.newBuilder()
                    .setHttpRequest(
                        com.google.cloud.tasks.v2.HttpRequest.newBuilder()
                            .setUrl("http://localhost:8080/process")
                            .setHttpMethod(HttpMethod.POST)
                            .setBody(ByteString.copyFrom(payload, StandardCharsets.UTF_8))
                            .build()
                    );

                Task task = tasksClient.createTask(parent, taskBuilder.build());
                emitter.complete(task.getName());
            } catch (IOException e) {
                LOG.errorf("Failed to enqueue task: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }
}
