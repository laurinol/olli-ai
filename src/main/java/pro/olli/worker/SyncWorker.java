/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.worker;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.apache.tika.Tika;
import org.jboss.logging.Logger;
import pro.olli.model.StorageCredentials;
import pro.olli.storage.StorageFactory;
import pro.olli.storage.StorageProvider;

import java.util.Map;
import pro.olli.storage.StorageProviderType;

/**
 * Worker for processing sync tasks from Cloud Tasks.
 */
@Startup
@Path("/process")
@ApplicationScoped
public class SyncWorker {

    private static final Logger LOG = Logger.getLogger(SyncWorker.class);

    private final Firestore firestore;
    private final StorageFactory storageFactory;
    private final Tika tika;

    public SyncWorker(Firestore firestore, StorageFactory storageFactory) {
        this.firestore = firestore;
        this.storageFactory = storageFactory;
        this.tika = new Tika();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Void> processTask(SyncTask task) {
        LOG.infof("Processing task: %s", task);
        return Uni.createFrom().future(firestore.collection("syncRequests").document(task.clientId).get())
            .onItem().transformToUni(syncDoc -> {
                if (!(syncDoc instanceof DocumentSnapshot) || !((DocumentSnapshot) syncDoc).exists()) {
                    LOG.errorf("Sync request not found: %s", task.clientId);
                    return Uni.createFrom().voidItem();
                }
                return Uni.createFrom().future(firestore.collection("clients").document(task.clientId).collection("users").document(task.userId).get())
                    .onItem().transformToUni(userDoc -> {
                        if (!(userDoc instanceof DocumentSnapshot) || !((DocumentSnapshot) userDoc).exists()) {
                            LOG.errorf("User not found: %s for client %s", task.userId, task.clientId);
                            return Uni.createFrom().voidItem();
                        }
                        StorageCredentials creds = new StorageCredentials((Map<String, Object>) userDoc.get("token"));
                        String folderId = task.folderId != null ? task.folderId : System.getenv("GOOGLE_TEST_FOLDER_ID");
                        StorageProviderType type = StorageProviderType.fromString(task.source);
                        if (type == null) {
                            LOG.errorf("Unsupported source: %s", task.source);
                            return Uni.createFrom().voidItem();
                        }
                        StorageProvider provider = storageFactory.getProvider(task.source);
                        return processTaskWithProvider(task, creds, provider);
                    });
            });
    }

    private Uni<Void> processTaskWithProvider(SyncTask task, StorageCredentials creds, StorageProvider provider) {
        return provider.authenticate(creds)
            .flatMap(authSuccess -> {
                if (!authSuccess) {
                    LOG.errorf("Authentication failed for source %s", task.source);
                    updateStatus(task.clientId, "failed");
                    return Uni.createFrom().voidItem();
                }
                return provider.listFiles(task.folderId)
                    .flatMap(files -> {
                        Uni<Void> result = Uni.createFrom().voidItem();
                        for (String file : files) {
                            result = result.flatMap(v ->
                                provider.downloadFile(file)
                                    .flatMap(stream -> {
                                        try {
                                            String content = tika.parseToString(stream);
                                            LOG.infof("Processed file %s for client %s", file, task.clientId);
                                            // Embed with Vertex AI, store in Vector Search (placeholder)
                                            return Uni.createFrom().voidItem();
                                        } catch (Exception e) {
                                            LOG.errorf("Failed to process file %s: %s", file, e.getMessage());
                                            return Uni.createFrom().voidItem();
                                        } finally {
                                            try {
                                                if (stream != null) stream.close();
                                            } catch (Exception e) {
                                                LOG.errorf("Failed to close stream: %s", e.getMessage());
                                            }
                                        }
                                    })
                            );
                        }
                        return result;
                    })
                    .invoke(() -> updateStatus(task.clientId, "completed"))
                    .onFailure().invoke(throwable -> {
                        LOG.errorf("Processing failed: %s", throwable.getMessage());
                        updateStatus(task.clientId, "failed");
                    });
            });
    }

    private void updateStatus(String clientId, String status) {
        firestore.collection("syncRequests").document(clientId)
            .update("status", status, "lastSync", System.currentTimeMillis());
    }

    public static class SyncTask {
        public String clientId; // businessId
        public String userId;   // sub from JWT
        public String source;
        public String folderId;

        @Override
        public String toString() {
            return "SyncTask{clientId='" + clientId + "', userId='" + userId + "', source='" + source + "', folderId='" + folderId + "'}";
        }
    }
}
